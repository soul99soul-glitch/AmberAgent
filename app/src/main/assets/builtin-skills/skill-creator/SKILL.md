---
name: skill-creator
version: 2.0.0
description: Use when the user wants to create a new AmberAgent skill, update an existing skill, package reusable instructions, or add specialized workflows, file handling, or tool-integration guidance.
---

# Skill Creator

Use this skill to create effective AmberAgent skills.

## About Skills

Skills are modular, self-contained packages that extend AmberAgent with specialized knowledge, workflows, and tool guidance. Treat them as onboarding guides for a domain or task: they give the agent procedural knowledge that should not have to be rediscovered every time.

### What Skills Provide

1. Specialized workflows for a domain or task.
2. Tool guidance for file formats, APIs, command-line tools, or Android capabilities.
3. Domain knowledge such as schemas, business rules, project conventions, or acceptance checks.
4. Bundled resources such as scripts, references, templates, and assets.

## Core Principles

### Concise Is Key

The context window is shared by the system prompt, conversation history, other skills, tool results, and the user's request.

Default assumption: the model is already capable. Add only context the model needs for this skill. Challenge each paragraph: does it justify its token cost?

Prefer concise examples over long explanations.

### Set Appropriate Degrees Of Freedom

Match specificity to task fragility:

- High freedom: text instructions when multiple approaches are valid.
- Medium freedom: pseudocode or parameterized scripts when a preferred pattern exists.
- Low freedom: exact scripts or exact sequences when operations are fragile or consistency is critical.

### Anatomy Of A Skill

Every skill has a required `SKILL.md` file and optional resources:

```text
skill-name/
├── SKILL.md
├── scripts/
├── references/
└── assets/
```

`SKILL.md` frontmatter must include:

- `name`: skill name.
- `description`: what the skill does and when to trigger it. This is the primary trigger signal, so include all important "when to use" cases here.

The body is loaded after the skill triggers. Keep it short. Move detailed references into `references/` when the body approaches 500 lines.

### Progressive Disclosure

Use three loading levels:

1. Metadata: always available.
2. `SKILL.md` body: loaded when the skill triggers.
3. Bundled resources: loaded only when needed.

## Skill Creation Process

1. Understand the skill by asking for or extracting concrete examples.
2. Plan the reusable instructions, scripts, references, and assets.
3. Create `SKILL.md` with proper frontmatter and concise instructions.
4. Test the skill on a real task.
5. Iterate based on actual usage.

## Writing `SKILL.md`

- Use imperative language.
- Put trigger conditions in the `description` field.
- Add only context the model needs.
- Prefer examples over prose.
- Keep the essential workflow in `SKILL.md`; move detailed reference material to separate files.
- Include acceptance checks when the skill is meant to produce code, files, or repeatable operations.

## What Not To Include

Do not create extra files such as `README.md`, `INSTALLATION_GUIDE.md`, or `CHANGELOG.md` unless the agent truly needs them to perform the skill. The package should contain only what helps the agent do the job.
