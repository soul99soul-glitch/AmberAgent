// The timeline owns one snapshot per message; lazy row destruction only releases views.
// These values restore user choices and drafts, never provider or Smart UI state.
export class MessageRowViewState {
  reasoningStates: Record<string, string> = {};
  askAnswers: Record<string, string> = {};
  askMultiAnswers: Record<string, string[]> = {};
  sawStream: boolean = false;
  lastMiniAppStreamText: string = '';
  lastMiniAppStreamMessageId: string = '';
  toolOutputExpanded: Map<string, boolean> = new Map<string, boolean>();
  councilObjectiveExpanded: Map<string, boolean> = new Map<string, boolean>();
  subAgentResultExpanded: Map<string, boolean> = new Map<string, boolean>();
  codeExpanded: Map<string, Record<string, boolean>> = new Map<string, Record<string, boolean>>();
  miniAppShowCode: Map<string, boolean> = new Map<string, boolean>();
  miniAppModifyRequest: Map<string, string> = new Map<string, string>();

  static copyTextValues(values: Record<string, string>): Record<string, string> {
    const copy: Record<string, string> = {};
    for (const key of Object.keys(values)) copy[key] = values[key];
    return copy;
  }

  static copySelections(values: Record<string, string[]>): Record<string, string[]> {
    const copy: Record<string, string[]> = {};
    for (const key of Object.keys(values)) copy[key] = values[key].slice();
    return copy;
  }
}
