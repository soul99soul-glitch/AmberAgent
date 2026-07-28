// recent_chats_prompt — Recent Chats 动态 system 块(D-077b)
// Android 基准:
//   GenerationPrompts.kt:219-241 buildRecentChatsPrompt(conversationRepo)
//   ConversationDAO.kt:29-30 getRecentConversations(is_pinned DESC, update_at DESC LIMIT 10)
//   TimeUtil.kt:14-21 Instant.toLocalDate(系统时区 + MEDIUM localized date)
//   core/agent-utils Json.kt:22-30 JsonInstantPretty(kotlinx prettyPrint 默认 4 空格)
// 偏差:
//   - toLocalDate 格式化在 entry(Intl medium date,PlaceholderSupport 同档);
//     HAR 收预格式化字符串(PD-004 时间戳链路延续)
//   - 仓库取数由调用方(ChatRepository.listRecent 既有端口,排序同 DAO)
export const RECENT_CHATS_PROMPT_LIMIT: number = 10;

export interface RecentChatItem {
  title: string;
  lastChat: string; // 预格式化(toLocalDate MEDIUM localized 语义)
}

// kotlinx Json prettyPrint(默认缩进 4 空格)对 [{title,last_chat}] 形状与
//   JSON.stringify(_, null, 4) 逐字节一致(扁平对象数组,无特殊转义差异)
export const prettyPrintRecentChatsJson = (items: RecentChatItem[]): string => {
  const arr: Array<Record<string, string>> = items.map(
    (i: RecentChatItem): Record<string, string> => ({
      title: i.title, last_chat: i.lastChat,
    }));
  return JSON.stringify(arr, null, 4);
};

// GenerationPrompts.kt:219-241 逐字(空列表 → '')
export const buildRecentChatsPrompt = (items: RecentChatItem[]): string => {
  if (items.length === 0) return '';
  const json: string = prettyPrintRecentChatsJson(items);
  return '\n**Recent Chats**\n'
    + "These are some of the user's recent conversations across AmberAgent."
    + ' You can use them to understand user preferences:\n'
    + json + '\n';
};
