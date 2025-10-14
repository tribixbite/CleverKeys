#!/usr/bin/env python3
"""
Generate ComposeKeyData.kt from Unicode compose sequence definitions.
Modified from Unexpected-Keyboard's compile.py to output Kotlin instead of Java.
"""

import sys, os, re, string

# Change to compose directory
compose_dir = "/data/data/com.termux/files/home/git/swype/Unexpected-Keyboard/srcs/compose"
os.chdir(compose_dir)

# Load compile.py functions without executing main code
code = open("compile.py").read()
code_lines = code.split('\n')
code_no_main = '\n'.join(code_lines[:323])  # Stop before line 323 (main execution)

# Execute function definitions
exec(code_no_main, globals())

# Generate Kotlin code
def gen_kotlin(entry_states, machine):
    def char_repr_kotlin(c):
        if isinstance(c, int):
            if c == -1:
                return "'\\uFFFF'"
            return f"'\\u{c:04x}'"
        if c == "\0":
            return "'\\u0000'"
        if c == '"':
            return "'\"'"
        if c == "'":
            return "'\\''"
        if c == '\\':
            return "'\\\\'"
        if c == '\n':
            return "'\\n'"
        if c == '\r':
            return "'\\r'"
        if ord(c) < 32 or ord(c) > 126:
            return f"'\\u{ord(c):04x}'"
        return f"'{c}'"

    # Generate states array
    states_chars = [s[0] for s in machine]
    states_repr = [char_repr_kotlin(c) for c in states_chars]

    # Generate edges array
    edges_ints = [s[1] for s in machine]
    edges_repr = [str(i) for i in edges_ints]

    # Format with line breaks every 12 elements
    def format_array(items, per_line=12):
        lines = []
        for i in range(0, len(items), per_line):
            lines.append(", ".join(items[i:i+per_line]))
        return ",\n        ".join(lines)

    states_formatted = format_array(states_repr)
    edges_formatted = format_array(edges_repr)

    # Generate constants
    constants = []
    for name, state in sorted(entry_states.items()):
        const_name = name.upper() if "_" in name else name
        constants.append(f"    const val {const_name} = {state}")

    constants_formatted = "\n".join(constants)

    return f"""package tribixbite.keyboard2

/**
 * Generated compose key data for CleverKeys.
 *
 * THIS FILE IS AUTO-GENERATED - DO NOT EDIT MANUALLY
 * Run: python3 generate_compose_data.py > src/main/kotlin/tribixbite/keyboard2/ComposeKeyData.kt
 *
 * Source: Unexpected-Keyboard/srcs/compose/*.json and compose/ directory
 * Generator: generate_compose_data.py (modified from compile.py)
 *
 * Contains {len(machine)} states for {len(entry_states)} entry points.
 */
object ComposeKeyData {{

    /**
     * State array representing compose sequence states and transitions.
     * See ComposeKey.apply() for the state machine interpreter.
     */
    @JvmField
    val states: CharArray = charArrayOf(
        {states_formatted}
    )

    /**
     * Edge array representing transition states and state sizes.
     * Must have the same length as states array.
     */
    @JvmField
    val edges: IntArray = intArrayOf(
        {edges_formatted}
    )

    // Entry point constants for each compose mode
{constants_formatted}

    /**
     * Validate the integrity of the compose key data.
     */
    fun validateData(): Boolean {{
        try {{
            if (states.size != edges.size) return false

            var i = 0
            while (i < states.size) {{
                val header = states[i].code
                val length = edges[i]

                when {{
                    header == 0 -> {{
                        if (length < 1 || i + length > states.size) return false
                        i += length
                    }}
                    header == 0xFFFF -> {{
                        if (length < 2 || i + length > states.size) return false
                        i += length
                    }}
                    header > 0 -> {{
                        if (length != 1) return false
                        i++
                    }}
                    else -> return false
                }}
            }}
            return true
        }} catch (e: Exception) {{
            return false
        }}
    }}

    /**
     * Get statistics about the compose key data.
     */
    fun getDataStatistics(): ComposeDataStatistics {{
        var intermediateStates = 0
        var characterFinalStates = 0
        var stringFinalStates = 0
        var totalTransitions = 0

        var i = 0
        while (i < states.size) {{
            val header = states[i].code
            val length = edges[i]

            when {{
                header == 0 -> {{
                    intermediateStates++
                    totalTransitions += length - 1
                    i += length
                }}
                header == 0xFFFF -> {{
                    stringFinalStates++
                    i += length
                }}
                header > 0 -> {{
                    characterFinalStates++
                    i++
                }}
                else -> i++
            }}
        }}

        return ComposeDataStatistics(
            totalStates = intermediateStates + characterFinalStates + stringFinalStates,
            intermediateStates = intermediateStates,
            characterFinalStates = characterFinalStates,
            stringFinalStates = stringFinalStates,
            totalTransitions = totalTransitions,
            dataSize = states.size
        )
    }}

    data class ComposeDataStatistics(
        val totalStates: Int,
        val intermediateStates: Int,
        val characterFinalStates: Int,
        val stringFinalStates: Int,
        val totalTransitions: Int,
        val dataSize: Int
    )
}}
"""

# Main execution
if __name__ == "__main__":
    total_sequences = 0
    tries = {}

    # Process all input files
    for fname in sorted(os.listdir(".")):
        if fname.endswith(".json") or fname == "compose":
            fpath = os.path.join(".", fname)
            tname, _ = os.path.splitext(fname)
            if os.path.isdir(fpath):
                sequences = parse_sequences_dir(fpath)
            else:
                sequences = parse_sequences_file(fpath)
            add_sequences_to_trie(sequences, tries.setdefault(tname, {}))
            total_sequences += len(sequences)

    if "compose" in tries:
        check_for_warnings(tries["compose"])

    entry_states, automata = make_automata(tries)

    # Output Kotlin code to stdout
    print(gen_kotlin(entry_states, automata))

    # Stats to stderr
    print(f"✅ Generated ComposeKeyData.kt: {total_sequences} sequences → {len(automata)} states", file=sys.stderr)
    print(f"   Entry points: {len(entry_states)} ({', '.join(sorted(entry_states.keys())[:5])}...)", file=sys.stderr)
    print(f"   Dropped {dropped_sequences} sequences, {warning_count} warnings", file=sys.stderr)
