#!/usr/bin/env python3
"""
Generate ComposeKeyData.kt from Unicode compose sequence definitions.
Modified from Unexpected-Keyboard's compile.py to output Kotlin instead of Java.
"""

import sys, os, re, string

# Save original directory
original_dir = os.getcwd()

# Change to compose directory
compose_dir = "/data/data/com.termux/files/home/git/swype/Unexpected-Keyboard/srcs/compose"
os.chdir(compose_dir)

# Load compile.py functions without executing main code
# Redirect stdout during exec to prevent warnings from polluting output
import io
code = open("compile.py").read()
code_lines = code.split('\n')
code_no_main = '\n'.join(code_lines[:323])  # Stop before line 323 (main execution)

# Execute function definitions with stdout redirected to stderr
old_stdout = sys.stdout
sys.stdout = sys.stderr
try:
    exec(code_no_main, globals())
finally:
    sys.stdout = old_stdout

# Generate binary data file and Kotlin loader code
def gen_kotlin_with_binary(entry_states, machine, binary_path):
    import struct

    # Generate states and edges arrays
    states_chars = [s[0] for s in machine]
    edges_ints = [s[1] for s in machine]

    # Convert chars to unicode code points
    states_ints = []
    for c in states_chars:
        if isinstance(c, int):
            states_ints.append(c if c != -1 else 0xFFFF)
        else:
            states_ints.append(ord(c))

    # Write binary file (format: int count, then char array as shorts, then int array)
    with open(binary_path, 'wb') as f:
        # Write array sizes
        f.write(struct.pack('>I', len(states_ints)))  # Big-endian int

        # Write states as shorts (16-bit)
        for code_point in states_ints:
            f.write(struct.pack('>H', code_point))  # Big-endian short

        # Write edges as ints (32-bit)
        for edge in edges_ints:
            f.write(struct.pack('>i', edge))  # Big-endian int

    # Generate constants
    constants = []
    for name, state in sorted(entry_states.items()):
        const_name = name.upper() if "_" in name else name
        constants.append(f"    const val {const_name} = {state}")

    constants_formatted = "\n".join(constants)

    return f"""package tribixbite.keyboard2

import android.content.Context
import java.io.DataInputStream

/**
 * Generated compose key data for CleverKeys.
 *
 * THIS FILE IS AUTO-GENERATED - DO NOT EDIT MANUALLY
 * Run: python3 generate_compose_data.py
 *
 * Source: Unexpected-Keyboard/srcs/compose/\*.json and compose/ directory
 * Generator: generate_compose_data.py (modified from compile.py)
 *
 * Contains {len(machine)} states for {len(entry_states)} entry points.
 * Data loaded from assets/compose_data.bin at runtime to avoid JVM 64KB method limit.
 */
object ComposeKeyData {{

    private var _states: CharArray? = null
    private var _edges: IntArray? = null

    /**
     * State array representing compose sequence states and transitions.
     * Loaded lazily from binary resource file.
     */
    @JvmField
    val states: CharArray
        get() = _states ?: throw IllegalStateException("ComposeKeyData not initialized. Call initialize(context) first.")

    /**
     * Edge array representing transition states and state sizes.
     * Must have the same length as states array.
     */
    @JvmField
    val edges: IntArray
        get() = _edges ?: throw IllegalStateException("ComposeKeyData not initialized. Call initialize(context) first.")

    /**
     * Initialize compose data from binary resource file.
     * Must be called before accessing states/edges arrays.
     */
    fun initialize(context: Context) {{
        if (_states != null) return  // Already initialized

        try {{
            context.assets.open("compose_data.bin").use {{ inputStream ->
                DataInputStream(inputStream).use {{ dis ->
                    // Read array size
                    val size = dis.readInt()

                    // Read states array (shorts → chars)
                    val statesArray = CharArray(size)
                    for (i in 0 until size) {{
                        statesArray[i] = dis.readUnsignedShort().toChar()
                    }}

                    // Read edges array (ints)
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

    /**
     * Entry point constants for each compose mode
     */
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

    # Redirect ALL stdout to stderr during processing (warnings/diagnostics)
    old_stdout = sys.stdout
    sys.stdout = sys.stderr
    try:
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
    finally:
        # Restore stdout for Kotlin code output
        sys.stdout = old_stdout

    # Write binary data file (use absolute path from original directory)
    binary_path = os.path.join(original_dir, "src/main/assets/compose_data.bin")
    os.makedirs(os.path.dirname(binary_path), exist_ok=True)

    # Output Kotlin code to stdout (only thing that should be captured by shell >)
    print(gen_kotlin_with_binary(entry_states, automata, binary_path))

    # Stats to stderr
    print(f"✅ Generated ComposeKeyData.kt: {total_sequences} sequences → {len(automata)} states", file=sys.stderr)
    print(f"   Entry points: {len(entry_states)} ({', '.join(sorted(entry_states.keys())[:5])}...)", file=sys.stderr)
    print(f"   Dropped {dropped_sequences} sequences, {warning_count} warnings", file=sys.stderr)
