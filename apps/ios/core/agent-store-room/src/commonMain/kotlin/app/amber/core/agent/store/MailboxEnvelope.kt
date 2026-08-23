package app.amber.core.agent.store

/** mailbox 信封类型（wire 字符串与实体 `type` 列同值；新类型追加即兼容）。 */
enum class MailboxEnvelopeType(val wireName: String) {
    MESSAGE("MESSAGE"),
    NEW_TASK("NEW_TASK"),
    FINAL_ANSWER("FINAL_ANSWER"),
}

/**
 * P1-b: 信封折入 user 消息的结构头渲染（与 P1.3(c) 设计的
 * `[mailbox {TYPE} from {authorPath}]` 头格式一致）。payload 原样跟在头后，
 * 与普通 steer 消息同通道进下一轮请求。
 */
fun renderMailboxEnvelopeToUserText(
    authorThreadId: String,
    type: String,
    payload: String,
): String = "[mailbox $type from $authorThreadId]\n$payload"
