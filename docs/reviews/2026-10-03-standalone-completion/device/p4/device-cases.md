# P4 actual independent-package device cases

Device: existing emulator 127.0.0.1:5555, 1320×2856. Novel app.amber.novel; DeepRead app.amber.deepread.reader. Synthetic local provider with fake key validates requests and product behavior; no real cloud quality or production signing claim.

| Case | Actual operation and outcome | Evidence |
| --- | --- | --- |
| Tools | Actual HTTP includes 22 novel tools + ask_user; rename title tool creates pending durable proposal, author approves, renamed project survives cold entry. Wrong name parameter was correctly rejected. | p4-tool-title-request, tool-rename-diff/approved, actual-tool-name-persisted; request-receipts.json |
| Paragraph patch | Full old/new first-paragraph tail visible; author approval changes only first paragraph, second canonical paragraph preserved; real old-version list remains. | p4-paragraph-preview-tails, reader-approved-body |
| Contract | Real one-click request now carries fixed planner system through creation/adapter/HTTP, returns six-field draft; confirm/save and two upcoming arc entries persist. | p4-final-contract-model-success, confirmed, arc-keyboard/saved |
| State rescue | Controlled 503 failure persists; editing body makes old retry stale; fresh full rescue completes 3/3. No half state publication. | state-controlled-failure/persisted, stale-retry, rescuecompleted |
| Same-page state refresh | Same event ID/source now immediately shows new summary; Courier alias merge immediately updates character aliases and experiences without leaving page. | live-before-rebuild/rebuilt-same-page, live-courier-before-merge/after-merge |
| Earlier fact | Actual audit supplied chapter-2 quote/range; View Earlier opens exact chapter/range 528–563. | audit-pair-result, canonical-reader-focus |
| Batch repair | Two nonoverlapping issues in same chapter generate two complete old/new previews; one approval applies both, preserves first paragraph and Courier clause. | all-repair-proposal/approved/reader-body |
| Reader author brief | Keyboard leaves input/footer usable; request contains P4_AUTHOR_DIRECTION, new candidate rendered, original chapter remains pending author adoption. | reader-brief-open/keyboard, revision-started/result-bottom/actions |
| Ghostwrite brief | Failed reviewed candidate retains same job and frozen plan ID; actual next writer carries author brief, previous candidate, contract and arc. Synthetic reviewer deliberately invalid JSON leaves retryable failure. Own job cancelled after verification. | ghost-author-brief-open/keyboard/same-job, ghostwrite-failed-controls; request-receipts.json |
| Author plan preview | Final new package displays Chinese confirmation and six author fields with 44vp footer, preserving original frozen model text. Only opened preview; did not start another job. | ghost-author-plan-final |

Independent visual review: p4-ui-review.md. Final counter-label repair is UI-only; /tmp/amber-p4-counter-label-final-build.log built/signed both packages, both installed, p4-candidate-label-final verifies 本轮候选 and full controls; own fixture job cancelled after capture. 480/480 Novel, 2617/2617 Chat, final 57/57 Entry tests passed; domains unchanged by the label repair. Independent final visual review in p4-ui-review.md.

Recovery sandbox boundary from P2 remains: no shipping debug route was added. System background/real cloud/production distribution are distinct external gates; P5–P8 finish implementation and report evidence separately.
