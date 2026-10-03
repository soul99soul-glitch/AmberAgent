import { classifyNovelMaterials } from './material_injection.ts';
import { invalidInput } from './error.ts';
import { novelChapterOrdinal } from './models.ts';
import type { NovelProject } from './models.ts';
import type { WorkspaceCas } from './workspace_history.ts';
import { makePolishContextSnapshotItem, makePolishChapterTarget, MAX_POLISH_CONTEXT_ITEMS, MAX_POLISH_CONTEXT_ITEM_CHARS, MAX_POLISH_CONTEXT_TOTAL_CHARS } from './polish.ts';
import type { PolishChapterTarget, PolishContextSnapshotItem, PolishContextOptions, PolishContextSnapshotKind } from './polish.ts';

export interface NovelPolishStartPreview {
  cas: WorkspaceCas;
  chapterIds: string[];
  targets: PolishChapterTarget[];
  polishPreference: string;
  preferenceSource: 'project' | 'none';
  contextOptions: PolishContextOptions;
  contextSnapshot: PolishContextSnapshotItem[];
}
export const selectedPolishTargets = (project: NovelProject, chapterIds: string[]): PolishChapterTarget[] => {
  if (chapterIds.length === 0 || new Set(chapterIds).size !== chapterIds.length) throw invalidInput('润色选章必须非空且不重复');
  return chapterIds.map(id => {
    const index = project.chapters.findIndex(chapter => chapter.id === id && !chapter.discarded);
    if (index < 0) throw invalidInput('润色章节不存在或已废弃');
    const chapter = project.chapters[index];
    return makePolishChapterTarget({ id, ordinal: novelChapterOrdinal(chapter, index + 1), title: chapter.title, sourceContent: chapter.content });
  }).sort((left, right) => left.ordinal - right.ordinal);
};
export const frozenPolishContext = (
  project: NovelProject, branchId: string, options: PolishContextOptions,
  targets: PolishChapterTarget[], plotContent: string | null,
): PolishContextSnapshotItem[] => {
  const items: PolishContextSnapshotItem[] = [];
  let total = 0;
  const append = (kind: PolishContextSnapshotKind, path: string, raw: string, protectedItem = false): void => {
    const content = raw.trim();
    if (content.length === 0) return;
    const remaining = MAX_POLISH_CONTEXT_TOTAL_CHARS - total;
    if (protectedItem && (items.length >= MAX_POLISH_CONTEXT_ITEMS || content.length > MAX_POLISH_CONTEXT_ITEM_CHARS || content.length > remaining)) {
      throw invalidInput('润色必需资料超出冻结上下文容量，请缩减资料后重试；未截断分支覆盖事实');
    }
    if (items.length >= MAX_POLISH_CONTEXT_ITEMS || remaining <= 0) return;
    const bounded = content.slice(0, Math.min(MAX_POLISH_CONTEXT_ITEM_CHARS, remaining)).trim();
    if (!bounded) return;
    items.push(makePolishContextSnapshotItem({ kind, sourcePath: path, content: bounded }));
    total += bounded.length;
  };
  const queryProject: NovelProject = { ...project, authorPlot: plotContent ?? undefined, chapters: project.chapters.filter(chapter => targets.some(target => target.id === chapter.id)) };
  for (const decision of classifyNovelMaterials(queryProject, targets.map(target => target.sourceContent).join('\n'))) {
    if (!decision.included) continue;
    const material = project.materials.find(item => item.id === decision.materialId)!;
    if (material.kind === 'character' && !options.includeCharacters && !decision.protected) continue;
    append(decision.protected && !options.includeCharacters ? 'material' : material.kind === 'character' ? 'character' : 'material',
      `branches/${branchId}/setting/${material.kind}/${material.id}.md`, decision.text, decision.protected);
  }
  if (options.includePlot && plotContent !== null) append('plot', `branches/${branchId}/plan/plot.md`, plotContent);
  if (options.includePlot) for (const pointer of project.chapterPlots.filter(item => !item.stale)) {
    append('plot', `branches/${branchId}/plot/chapters/${pointer.chapterId}.md`, `正文摘录（证据以原章为准）\n${pointer.text}`);
  }
  if (options.includeForeshadows) for (const item of project.branchSettings.foreshadows) {
    append('foreshadow', `branches/${branchId}/setting/catalog.json/foreshadow/${item.id}`, `# ${item.title}\n状态: ${item.status}\n${item.content}`);
  }
  if (options.includeDecisions) for (const item of project.branchSettings.confirmedDecisions) {
    append('decision', `branches/${branchId}/setting/catalog.json/decision/${item.id}`, `# ${item.title}\n${item.content}`);
  }
  return items;
};
