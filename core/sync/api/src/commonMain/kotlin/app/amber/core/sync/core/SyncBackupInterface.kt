package app.amber.core.sync.core

interface SyncBackupInterface {
    suspend fun exportSettings(passphrase: String?): ByteArray

    suspend fun importSettings(data: ByteArray, passphrase: String?): SyncPreview
}
