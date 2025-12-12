#!/usr/bin/env python3
"""
Generate compose_data.bin from compose sequence source files.

This script compiles compose sequences from JSON files into an efficient binary
state machine that can be loaded at runtime by CleverKeys.

Based on Unexpected-Keyboard's srcs/compose/compile.py, modified to output
binary format instead of Java source code.

Binary format:
  - int32: array size (number of states)
  - uint16[size]: states array (characters)
  - int32[size]: edges array (state indices/sizes)

Usage:
  python3 scripts/generate_compose_bin.py srcs/compose/*.json srcs/compose/compose
"""

import sys
import os
import re
import json
import struct
from array import array

dropped_sequences = 0
warning_count = 0


def seq_to_str(s, result=None):
    """Convert sequence to string for debug output."""
    msg = "+".join(s)
    return msg if result is None else msg + " = " + result


def warn(msg, seq=None, result=None):
    """Print a warning message."""
    global warning_count
    if seq is not None:
        msg = f"Sequence {seq_to_str(seq, result=result)} {msg}"
    print(f"Warning: {msg}", file=sys.stderr)
    warning_count += 1


def parse_keysymdef_h(fname):
    """Parse symbol names from keysymdef.h."""
    with open(fname, "r") as inp:
        keysym_re = re.compile(r'^#define XK_(\S+)\s+\S+\s*/\*.U\+([0-9a-fA-F]+)\s')
        for line in inp:
            m = re.match(keysym_re, line)
            if m is not None:
                yield (m.group(1), chr(int(m.group(2), 16)))


def strip_cstyle_comments(inp):
    """Remove C-style // comments from JSON input."""
    def strip_line(line):
        i = line.find("//")
        return line[:i] + "\n" if i >= 0 else line
    return "".join(map(strip_line, inp))


def parse_sequences_file_json(fname):
    """Parse compose sequences from a JSON file."""
    def tree_to_seqs(tree, prefix):
        for c, r in tree.items():
            if isinstance(r, str):
                yield prefix + [c], r
            else:
                yield from tree_to_seqs(r, prefix + [c])
    try:
        with open(fname, "r") as inp:
            tree = json.loads(strip_cstyle_comments(inp))
        return list(tree_to_seqs(tree, []))
    except Exception as e:
        warn("Failed parsing '%s': %s" % (fname, str(e)))
        return []


def parse_sequences_file_xkb(fname, xkb_char_extra_names):
    """Parse XKB's Compose.pre files."""
    global dropped_sequences
    line_re = re.compile(r'^((?:\s*<[^>]+>)+)\s*:\s*"((?:[^"\\]+|\\.)+)"\s*(\S+)?\s*(?:#.+)?$')
    char_re = re.compile(r'\s*<(?:U([a-fA-F0-9]{4,6})|([^>]+))>')

    char_names = {**xkb_char_extra_names}

    def parse_seq_char(sc):
        uchar, named_char = sc
        if uchar != "":
            c = chr(int(uchar, 16))
        elif len(named_char) == 1:
            c = named_char
        else:
            if named_char not in char_names:
                raise Exception("Unknown char: " + named_char)
            c = char_names[named_char]
        if len(c) > 1 or ord(c[0]) > 65535:
            raise Exception("Char out of range")
        return c

    def parse_seq_chars(def_):
        return list(map(parse_seq_char, re.findall(char_re, def_)))

    def parse_seq_result(r):
        if len(r) == 2 and r[0] == '\\':
            return r[1]
        return r

    # Populate char_names
    with open(fname, "r") as inp:
        for line in inp:
            m = re.match(line_re, line)
            if m is None or m.group(3) is None:
                continue
            try:
                char_names[m.group(3)] = parse_seq_result(m.group(2))
            except Exception:
                pass

    # Parse sequences
    prefix = "<Multi_key>"
    seqs = []
    with open(fname, "r") as inp:
        for line in inp:
            if not line.startswith(prefix):
                continue
            m = re.match(line_re, line[len(prefix):])
            if m is None:
                continue
            try:
                def_ = parse_seq_chars(m.group(1))
                result = parse_seq_result(m.group(2))
                seqs.append((def_, result))
            except Exception:
                dropped_sequences += 1
    return seqs


def parse_sequences_file(fname, xkb_char_extra_names={}):
    """Parse sequences from a file based on its extension."""
    if fname.endswith(".pre"):
        return parse_sequences_file_xkb(fname, xkb_char_extra_names)
    if fname.endswith(".json"):
        return parse_sequences_file_json(fname)
    raise Exception(fname + ": Unsupported format")


def parse_sequences_dir(dname):
    """Parse all sequence files in a directory."""
    compose_files = []
    xkb_char_extra_names = {}

    for fbasename in os.listdir(dname):
        fname = os.path.join(dname, fbasename)
        if fbasename == "keysymdef.h":
            xkb_char_extra_names = dict(parse_keysymdef_h(fname))
        else:
            compose_files.append(fname)

    sequences = []
    for fname in compose_files:
        sequences.extend(parse_sequences_file(fname, xkb_char_extra_names))
    return sequences


def add_sequences_to_trie(seqs, trie):
    """Add sequences to a trie structure."""
    global dropped_sequences

    def add_seq_to_trie(seq, result):
        t_ = trie
        for c in seq[:-1]:
            t_ = t_.setdefault(c, {})
            if isinstance(t_, str):
                return False
        c = seq[-1]
        if c in t_:
            return False
        t_[c] = result
        return True

    for seq, result in seqs:
        if not add_seq_to_trie(seq, result):
            dropped_sequences += 1


def make_automata(tries):
    """Compile tries into a state machine."""
    previous_leafs = {}
    states = []

    def add_tree(t):
        this_node_index = len(states)
        i = len(states)
        s = len(t.keys())
        states.append((0, s + 1))  # Node header: null char, size
        i += 1
        for c in range(s):
            states.append((None, None))
        for c in sorted(t.keys()):
            states[i] = (ord(c[0]) if len(c) == 1 else ord(c), add_node(t[c]))
            i += 1
        return this_node_index

    def add_leaf(c):
        if c in previous_leafs:
            return previous_leafs[c]
        this_node_index = len(states)
        previous_leafs[c] = this_node_index

        if c.startswith(":"):
            c = c[1:]

        if len(c) > 1 or ord(c[0]) > 32767:  # String final state
            javachars = array('H', c.encode("UTF-16-LE"))
            states.append((0xFFFF, len(javachars) + 1))  # -1 as unsigned
            for ch in javachars:
                states.append((ch, 0))
        else:  # Character final state
            states.append((ord(c[0]), 1))
        return this_node_index

    def add_node(n):
        if isinstance(n, str):
            return add_leaf(n)
        else:
            return add_tree(n)

    states.append((1, 1))  # Empty state at beginning
    entry_states = {n: add_tree(root) for n, root in tries.items()}
    return entry_states, states


def write_binary(entry_states, machine, output_path):
    """Write the state machine to a binary file."""
    size = len(machine)

    with open(output_path, 'wb') as f:
        # Write array size
        f.write(struct.pack('>i', size))

        # Write states array (unsigned shorts)
        for state, _ in machine:
            if isinstance(state, int):
                f.write(struct.pack('>H', state & 0xFFFF))
            else:
                f.write(struct.pack('>H', ord(state[0]) if state else 0))

        # Write edges array (ints)
        for _, edge in machine:
            f.write(struct.pack('>i', edge if edge is not None else 0))

    return size


def generate_kotlin_constants(entry_states, output_path):
    """Generate ComposeKeyData.kt with entry point constants."""
    template = '''package tribixbite.cleverkeys

import android.content.Context
import java.io.DataInputStream

/**
 * Generated compose key data for CleverKeys.
 *
 * THIS FILE IS AUTO-GENERATED - DO NOT EDIT MANUALLY
 * Run: python3 scripts/generate_compose_bin.py
 *
 * Source: srcs/compose/ (JSON files and compose/ directory)
 * Data loaded from assets/compose_data.bin at runtime to avoid JVM 64KB method limit.
 */
object ComposeKeyData {{

    private var _states: CharArray? = null
    private var _edges: IntArray? = null

    val states: CharArray
        get() = _states ?: throw IllegalStateException("ComposeKeyData not initialized. Call initialize(context) first.")

    val edges: IntArray
        get() = _edges ?: throw IllegalStateException("ComposeKeyData not initialized. Call initialize(context) first.")

    fun initialize(context: Context) {{
        if (_states != null) return

        try {{
            context.assets.open("compose_data.bin").use {{ inputStream ->
                DataInputStream(inputStream).use {{ dis ->
                    val size = dis.readInt()
                    val statesArray = CharArray(size)
                    for (i in 0 until size) {{
                        statesArray[i] = dis.readUnsignedShort().toChar()
                    }}
                    val edgesArray = IntArray(size)
                    for (i in 0 until size) {{
                        edgesArray[i] = dis.readInt()
                    }}
                    _states = statesArray
                    _edges = edgesArray
                }}
            }}
        }} catch (e: Exception) {{
            throw RuntimeException("Failed to load compose data from assets", e)
        }}
    }}

{constants}
}}
'''

    # Generate constant definitions
    constants = []
    for name, state in sorted(entry_states.items(), key=lambda x: x[1]):
        const_name = name.upper() if name != "compose" else "compose"
        if name == "fn":
            const_name = "fn"
        constants.append(f"    const val {const_name} = {state}")

    content = template.format(constants="\n".join(constants))

    with open(output_path, 'w') as f:
        f.write(content)


def main():
    if len(sys.argv) < 2:
        print("Usage: generate_compose_bin.py <input files/dirs...>", file=sys.stderr)
        print("Output: src/main/assets/compose_data.bin", file=sys.stderr)
        sys.exit(1)

    global dropped_sequences, warning_count
    total_sequences = 0
    tries = {}

    for fname in sorted(sys.argv[1:]):
        tname = os.path.splitext(os.path.basename(fname))[0]
        if os.path.isdir(fname):
            sequences = parse_sequences_dir(fname)
        else:
            sequences = parse_sequences_file(fname)
        add_sequences_to_trie(sequences, tries.setdefault(tname, {}))
        total_sequences += len(sequences)

    entry_states, automata = make_automata(tries)

    # Write binary file
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_dir = os.path.dirname(script_dir)
    output_bin = os.path.join(project_dir, "src/main/assets/compose_data.bin")
    output_kt = os.path.join(project_dir, "src/main/kotlin/tribixbite/cleverkeys/ComposeKeyData.kt")

    size = write_binary(entry_states, automata, output_bin)
    generate_kotlin_constants(entry_states, output_kt)

    print(f"Compiled {total_sequences} sequences into {size} states.", file=sys.stderr)
    print(f"Dropped {dropped_sequences} sequences. Generated {warning_count} warnings.", file=sys.stderr)
    print(f"Output: {output_bin} ({os.path.getsize(output_bin)} bytes)", file=sys.stderr)
    print(f"Constants: {output_kt}", file=sys.stderr)

    # Print entry points
    print("\nEntry points:", file=sys.stderr)
    for name, state in sorted(entry_states.items(), key=lambda x: x[1]):
        print(f"  {name}: {state}", file=sys.stderr)


if __name__ == "__main__":
    main()
