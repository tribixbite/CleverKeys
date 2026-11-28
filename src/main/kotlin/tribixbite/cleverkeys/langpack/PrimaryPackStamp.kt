package tribixbite.cleverkeys.langpack

import java.io.File

/**
 * The primary language and its installed pack's dictionary file as the keyboard last loaded it
 * ([dictionary] null = no pack, a bundled dictionary). CleverKeysService keeps one so that a
 * language-packs broadcast ([primaryNeedsReload]) can tell a re-imported or removed PRIMARY pack
 * (audit B5, 2026-11-26: only the secondary was reloaded). Pure JVM; extracted from the service
 * for a pure test (test-gap audit 2026-11-28, item 12).
 *
 * The file part is `path:length:lastModified`, the idiom the swipe engines' pack fingerprints use
 * too. A same-size re-import is still a change: the importer copies the dictionary with
 * `File.copyTo`, which gives the installed file the copy's time, not the source's
 * (PrimaryPackStampTest.aReimportCopyGetsAFreshModificationTime). Only a re-import within the same
 * millisecond, or after the clock went back to exactly the old time, would read as unchanged.
 * TODO(test-gap item 12): an install id written by LanguagePackManager.stageAndSwap would make all
 *  four fingerprints exact; not needed while every install writes a fresh file.
 */
internal data class PrimaryPackStamp(val language: String, val dictionary: String?) {

    companion object {
        /** The file part of a stamp. */
        fun fingerprint(path: String, length: Long, lastModified: Long): String = "$path:$length:$lastModified"

        fun of(language: String, path: String, length: Long, lastModified: Long): PrimaryPackStamp =
            PrimaryPackStamp(language, fingerprint(path, length, lastModified))

        /** [language] with its installed dictionary [file] (null when it has no pack). */
        fun of(language: String, file: File?): PrimaryPackStamp =
            PrimaryPackStamp(language, file?.let { fingerprint(it.path, it.length(), it.lastModified()) })

        /**
         * Must the primary's dictionary be reloaded now that [now] is what is installed? Only for
         * the SAME primary language whose pack changed (imported, re-imported, removed): a
         * language change reloads through ActiveLanguageSync instead, and the first observation
         * ([known] null) has nothing to compare with.
         */
        fun primaryNeedsReload(known: PrimaryPackStamp?, now: PrimaryPackStamp): Boolean =
            known != null && known.language == now.language && known.dictionary != now.dictionary
    }
}
