package app.amber.agent.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * P8-09 — 主题库：用户导入的主题包（原始 JSON 原样保存，round-trip 不丢字段）。
 * 内置主题不落库（固定列表），导入时校验拒绝 `builtin:` 前缀 id，
 * 因此本表永远无法覆盖内置主题。
 */
@Entity(tableName = "theme_package")
data class ThemePackageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    /** 导入时的原始包 JSON（含未知 token，保留）。 */
    @ColumnInfo(name = "json") val json: String,
    @ColumnInfo(name = "imported_at_ms") val importedAtMs: Long,
)
