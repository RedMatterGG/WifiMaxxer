package com.wifimaxxer

private const val BONDING_BACKUP = "/data/adb/wifimaxxer/bonding"

data class BondingState(
    val band24: Boolean?,
    val band5: Boolean?,
    val source: String?,
    val backup: String?,
    val modifiedSinceBackup: Boolean,
    val editable: Boolean,
    val detail: String
)

data class BondingWrite(val source: String, val backup: String)

fun parseBondingConfig(text: String): Pair<Boolean?, Boolean?> {
    fun value(key: String) = Regex("(?m)^\\s*$key\\s*=\\s*(\\d+)\\s*(?:[#;].*)?$")
        .findAll(text).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()?.let { it != 0 }
    return value("gChannelBondingMode24GHz") to value("gChannelBondingMode5GHz")
}

private val locateBondingFile = """
    locate_bonding_file() {
        /system/bin/find /vendor /odm /product /system_ext /system -type f -name WCNSS_qcom_cfg.ini 2>/dev/null |
        while IFS= read -r candidate; do
            case "${'$'}candidate" in
                /system/vendor/*|/system/odm/*|/system/product/*|/system/system_ext/*) continue ;;
                *[!A-Za-z0-9_./-]*) continue ;;
            esac
            if /system/bin/grep -Eq '^[[:space:]]*(gChannelBondingMode24GHz|gChannelBondingMode5GHz)[[:space:]]*=' "${'$'}candidate"; then
                printf '%s\n' "${'$'}candidate"
            fi
        done
    }
    matches=${'$'}(locate_bonding_file)
    count=${'$'}(printf '%s\n' "${'$'}matches" | /system/bin/sed '/^${'$'}/d' | /system/bin/wc -l | /system/bin/tr -d '[:space:]')
    [ "${'$'}count" = 1 ] || {
        if [ "${'$'}count" = 0 ]; then
            echo 'No Qualcomm WCNSS configuration with channel-bonding keys was found.' >&2
        else
            echo 'More than one Qualcomm WCNSS configuration contains bonding keys; direct editing is blocked because the active file is ambiguous.' >&2
            printf '%s\n' "${'$'}matches" >&2
        fi
        exit 3
    }
    file=${'$'}matches
""".trimIndent()

fun detectBonding(): BondingState {
    val command = """
        [ "${'$'}(/system/bin/id -u)" = 0 ] || exit 1
        $locateBondingFile
        printf 'WM_PATH=%s\n' "${'$'}file"
        if [ -f '$BONDING_BACKUP/original.ini' ] && [ -f '$BONDING_BACKUP/original.path' ] &&
           [ -f '$BONDING_BACKUP/original.sha256' ] && [ -f '$BONDING_BACKUP/original.fingerprint' ]; then
            set -- ${'$'}(/system/bin/sha256sum '$BONDING_BACKUP/original.ini')
            if [ "${'$'}1" = "${'$'}(/system/bin/cat '$BONDING_BACKUP/original.sha256')" ] &&
               [ "${'$'}file" = "${'$'}(/system/bin/cat '$BONDING_BACKUP/original.path')" ] &&
               [ "${'$'}(/system/bin/getprop ro.build.fingerprint)" = "${'$'}(/system/bin/cat '$BONDING_BACKUP/original.fingerprint')" ]; then
                printf 'WM_BACKUP=%s\n' '$BONDING_BACKUP/original.ini'
                /system/bin/cmp -s "${'$'}file" '$BONDING_BACKUP/original.ini' || printf 'WM_MODIFIED=1\n'
            else
                printf 'WM_BACKUP_INVALID=1\n'
            fi
        elif [ -e '$BONDING_BACKUP/original.ini' ]; then
            printf 'WM_BACKUP_INVALID=1\n'
        fi
        /system/bin/cat "${'$'}file"
    """.trimIndent()
    val result = RootAccess.run(command, 30)
    if (result.code != 0) return BondingState(null, null, null, null, false, false,
        "Direct bonding control unavailable. ${result.error.ifBlank { result.output }.trim().take(500)}")
    val path = Regex("(?m)^WM_PATH=(.+)$").find(result.output)?.groupValues?.get(1)?.trim()
    val backup = Regex("(?m)^WM_BACKUP=(.+)$").find(result.output)?.groupValues?.get(1)?.trim()
    val invalidBackup = Regex("(?m)^WM_BACKUP_INVALID=1$").containsMatchIn(result.output)
    val (band24, band5) = parseBondingConfig(result.output)
    val modified = Regex("(?m)^WM_MODIFIED=1$").containsMatchIn(result.output)
    return BondingState(band24, band5, path, backup, modified, !invalidBackup && path != null && (band24 != null || band5 != null),
        when {
            invalidBackup -> "A saved backup exists but its path, checksum, metadata, or ROM fingerprint does not match. Automatic writes and Restore are blocked."
            backup != null && modified -> "The live Qualcomm file differs from the saved original. Restore remains available."
            backup != null -> "The verified original backup is ready. The live file currently matches it."
            else -> "OEM values detected. The first Apply will save and verify the complete original file before changing it."
        })
}

internal fun bondingApplyScript(band24: Boolean?, band5: Boolean?): String {
    require(band24 != null || band5 != null)
    val value24 = band24?.let { if (it) "1" else "0" }.orEmpty()
    val value5 = band5?.let { if (it) "1" else "0" }.orEmpty()
    return """
        [ "${'$'}(/system/bin/id -u)" = 0 ] || exit 1
        $locateBondingFile
        size=${'$'}(/system/bin/stat -c %s "${'$'}file" 2>/dev/null) || exit 4
        [ "${'$'}size" -gt 0 ] && [ "${'$'}size" -le 1048576 ] || { echo 'Qualcomm configuration has an unsafe size.' >&2; exit 4; }
        backup='$BONDING_BACKUP'
        fingerprint=${'$'}(/system/bin/getprop ro.build.fingerprint)
        [ -n "${'$'}fingerprint" ] || { echo 'The ROM fingerprint is unavailable.' >&2; exit 5; }
        /system/bin/mkdir -p "${'$'}backup" || exit 5
        /system/bin/chmod 0700 "${'$'}backup" || exit 5
        if [ -e "${'$'}backup/original.ini" ]; then
            [ -f "${'$'}backup/original.path" ] && [ -f "${'$'}backup/original.sha256" ] && [ -f "${'$'}backup/original.fingerprint" ] || { echo 'Existing backup metadata is incomplete.' >&2; exit 6; }
            [ "${'$'}(/system/bin/cat "${'$'}backup/original.path")" = "${'$'}file" ] || { echo 'Saved backup belongs to a different Qualcomm file.' >&2; exit 6; }
            [ "${'$'}(/system/bin/cat "${'$'}backup/original.fingerprint")" = "${'$'}fingerprint" ] || { echo 'Saved backup belongs to a different ROM build.' >&2; exit 6; }
            set -- ${'$'}(/system/bin/sha256sum "${'$'}backup/original.ini")
            [ "${'$'}1" = "${'$'}(/system/bin/cat "${'$'}backup/original.sha256")" ] || { echo 'Saved original backup failed checksum verification.' >&2; exit 6; }
        else
            temp="${'$'}backup/original.ini.tmp.${'$'}${'$'}"
            /system/bin/cp -p "${'$'}file" "${'$'}temp" || exit 7
            /system/bin/sync
            /system/bin/cmp -s "${'$'}file" "${'$'}temp" || { /system/bin/rm -f "${'$'}temp"; echo 'Original backup verification failed.' >&2; exit 7; }
            set -- ${'$'}(/system/bin/sha256sum "${'$'}temp")
            printf '%s\n' "${'$'}file" > "${'$'}backup/original.path.tmp" || exit 7
            printf '%s\n' "${'$'}1" > "${'$'}backup/original.sha256.tmp" || exit 7
            printf '%s\n' "${'$'}fingerprint" > "${'$'}backup/original.fingerprint.tmp" || exit 7
            /system/bin/mv "${'$'}temp" "${'$'}backup/original.ini" &&
            /system/bin/mv "${'$'}backup/original.path.tmp" "${'$'}backup/original.path" &&
            /system/bin/mv "${'$'}backup/original.sha256.tmp" "${'$'}backup/original.sha256" &&
            /system/bin/mv "${'$'}backup/original.fingerprint.tmp" "${'$'}backup/original.fingerprint" || exit 7
            /system/bin/sync
        fi
        pre="${'$'}backup/prewrite.${'$'}${'$'}"
        stage="${'$'}backup/stage.${'$'}${'$'}"
        /system/bin/cp -p "${'$'}file" "${'$'}pre" && /system/bin/cp -p "${'$'}file" "${'$'}stage" || exit 8
        cleanup() {
            code=${'$'}?
            trap - EXIT HUP INT TERM
            /system/bin/rm -f "${'$'}pre" "${'$'}stage"
            if [ "${'$'}remounted" = 1 ]; then /system/bin/mount -o remount,ro "${'$'}mount_point" >/dev/null 2>&1 || true; fi
            exit "${'$'}code"
        }
        remounted=0
        trap cleanup EXIT HUP INT TERM
        want24='$value24'
        want5='$value5'
        if [ -n "${'$'}want24" ]; then
            /system/bin/grep -Eq '^[[:space:]]*gChannelBondingMode24GHz[[:space:]]*=' "${'$'}stage" || { echo '2.4 GHz key disappeared before the write.' >&2; exit 9; }
            /system/bin/sed -i "s/^\\([[:space:]]*gChannelBondingMode24GHz[[:space:]]*=[[:space:]]*\\)[0-9][0-9]*/\\1${'$'}want24/" "${'$'}stage" || exit 9
        fi
        if [ -n "${'$'}want5" ]; then
            /system/bin/grep -Eq '^[[:space:]]*gChannelBondingMode5GHz[[:space:]]*=' "${'$'}stage" || { echo '5 GHz key disappeared before the write.' >&2; exit 9; }
            /system/bin/sed -i "s/^\\([[:space:]]*gChannelBondingMode5GHz[[:space:]]*=[[:space:]]*\\)[0-9][0-9]*/\\1${'$'}want5/" "${'$'}stage" || exit 9
        fi
        if [ -n "${'$'}want24" ]; then /system/bin/grep -Eq "^[[:space:]]*gChannelBondingMode24GHz[[:space:]]*=[[:space:]]*${'$'}want24([[:space:]]|[;#]|${'$'})" "${'$'}stage" || exit 10; fi
        if [ -n "${'$'}want5" ]; then /system/bin/grep -Eq "^[[:space:]]*gChannelBondingMode5GHz[[:space:]]*=[[:space:]]*${'$'}want5([[:space:]]|[;#]|${'$'})" "${'$'}stage" || exit 10; fi
        mount_point=${'$'}(/system/bin/awk -v p="${'$'}file" '{ m=${'$'}2; fits=(m=="/" || p==m || index(p,m "/")==1); if (fits && length(m)>best) { best=length(m); chosen=m } } END { print chosen }' /proc/mounts)
        [ -n "${'$'}mount_point" ] || { echo 'Could not identify the filesystem containing the Qualcomm file.' >&2; exit 11; }
        options=${'$'}(/system/bin/awk -v m="${'$'}mount_point" '${'$'}2==m { print ${'$'}4; exit }' /proc/mounts)
        case ",${'$'}options," in
            *,rw,*) ;;
            *) /system/bin/mount -o remount,rw "${'$'}mount_point" || { echo 'The Qualcomm partition is read-only and could not be remounted. Direct editing is unsupported on this ROM.' >&2; exit 12; }; remounted=1 ;;
        esac
        if ! /system/bin/cp -p "${'$'}stage" "${'$'}file" || ! /system/bin/cmp -s "${'$'}stage" "${'$'}file"; then
            /system/bin/cp -p "${'$'}pre" "${'$'}file" >/dev/null 2>&1 || true
            /system/bin/sync
            echo 'The live-file write did not verify; the pre-write copy was restored.' >&2
            exit 13
        fi
        /system/bin/sync
        printf 'WM_PATH=%s\nWM_BACKUP=%s\n' "${'$'}file" "${'$'}backup/original.ini"
    """.trimIndent()
}

fun applyBonding(band24: Boolean?, band5: Boolean?): BondingWrite {
    val output = RootAccess.run(bondingApplyScript(band24, band5), 90).checked()
    val source = checkNotNull(Regex("(?m)^WM_PATH=(.+)$").find(output)?.groupValues?.get(1)?.trim())
    val backup = checkNotNull(Regex("(?m)^WM_BACKUP=(.+)$").find(output)?.groupValues?.get(1)?.trim())
    return BondingWrite(source, backup)
}

internal val bondingRestoreScript = """
    [ "${'$'}(/system/bin/id -u)" = 0 ] || exit 1
    backup='$BONDING_BACKUP'
    [ -f "${'$'}backup/original.ini" ] && [ -f "${'$'}backup/original.path" ] && [ -f "${'$'}backup/original.sha256" ] && [ -f "${'$'}backup/original.fingerprint" ] || { echo 'No complete Qualcomm backup is available.' >&2; exit 2; }
    file=${'$'}(/system/bin/cat "${'$'}backup/original.path")
    case "${'$'}file" in /vendor/*|/odm/*|/product/*|/system_ext/*|/system/*) ;; *) echo 'The backup path is invalid.' >&2; exit 3;; esac
    case "${'$'}file" in *[!A-Za-z0-9_./-]*) echo 'The backup path contains unsupported characters.' >&2; exit 3;; esac
    [ -f "${'$'}file" ] || { echo 'The original Qualcomm path no longer exists.' >&2; exit 3; }
    [ "${'$'}(/system/bin/getprop ro.build.fingerprint)" = "${'$'}(/system/bin/cat "${'$'}backup/original.fingerprint")" ] || { echo 'The saved original belongs to a different ROM build.' >&2; exit 4; }
    set -- ${'$'}(/system/bin/sha256sum "${'$'}backup/original.ini")
    [ "${'$'}1" = "${'$'}(/system/bin/cat "${'$'}backup/original.sha256")" ] || { echo 'The original backup failed checksum verification.' >&2; exit 4; }
    mount_point=${'$'}(/system/bin/awk -v p="${'$'}file" '{ m=${'$'}2; fits=(m=="/" || p==m || index(p,m "/")==1); if (fits && length(m)>best) { best=length(m); chosen=m } } END { print chosen }' /proc/mounts)
    [ -n "${'$'}mount_point" ] || exit 5
    options=${'$'}(/system/bin/awk -v m="${'$'}mount_point" '${'$'}2==m { print ${'$'}4; exit }' /proc/mounts)
    remounted=0
    cleanup() {
        code=${'$'}?
        trap - EXIT HUP INT TERM
        if [ "${'$'}remounted" = 1 ]; then /system/bin/mount -o remount,ro "${'$'}mount_point" >/dev/null 2>&1 || true; fi
        exit "${'$'}code"
    }
    trap cleanup EXIT HUP INT TERM
    case ",${'$'}options," in
        *,rw,*) ;;
        *) /system/bin/mount -o remount,rw "${'$'}mount_point" || { echo 'The Qualcomm partition could not be remounted for restore.' >&2; exit 6; }; remounted=1 ;;
    esac
    /system/bin/cp -p "${'$'}backup/original.ini" "${'$'}file" || exit 7
    /system/bin/sync
    /system/bin/cmp -s "${'$'}backup/original.ini" "${'$'}file" || { echo 'Restored file did not match the saved original.' >&2; exit 8; }
    printf 'WM_PATH=%s\nWM_BACKUP=%s\n' "${'$'}file" "${'$'}backup/original.ini"
""".trimIndent()

fun restoreBonding(): BondingWrite {
    val output = RootAccess.run(bondingRestoreScript, 90).checked()
    val source = checkNotNull(Regex("(?m)^WM_PATH=(.+)$").find(output)?.groupValues?.get(1)?.trim())
    val backup = checkNotNull(Regex("(?m)^WM_BACKUP=(.+)$").find(output)?.groupValues?.get(1)?.trim())
    return BondingWrite(source, backup)
}
