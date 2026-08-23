package app.amber.feature.novel.serialization

/**
 * Documented Swift Codable wire-format contract for NovelProjectDocumentV1 / .ambernovel.
 *
 * Phase 0 captures the contract and proves kotlinx defaults are incompatible.
 * Phase 1 implements custom serializers that produce and consume this shape.
 */
object NovelSwiftWireContract {
    const val PACKAGE_FORMAT = "amber.novel.project"
    const val ENVELOPE_VERSION = 1
    const val PROJECT_SCHEMA_VERSION = 1
    const val PACKAGE_EXTENSION = "ambernovel"
    const val PACKAGE_MIME = "application/vnd.amberagent.novel+json"

    /** Maximum raw project JSON bytes. */
    const val MAX_PROJECT_BYTES = 100 * 1024 * 1024

    /** Maximum package envelope bytes. */
    const val MAX_ENVELOPE_BYTES = 140 * 1024 * 1024

    /**
     * Swift `Date` is seconds since 2001-01-01 00:00:00 UTC.
     * Unix epoch 0 encodes as this value.
     */
    const val SWIFT_REFERENCE_DATE_UNIX = 978_307_200.0

    fun unixToSwiftDateSeconds(unixSeconds: Double): Double =
        unixSeconds - SWIFT_REFERENCE_DATE_UNIX

    fun swiftDateSecondsToUnix(swiftSeconds: Double): Double =
        swiftSeconds + SWIFT_REFERENCE_DATE_UNIX
}
