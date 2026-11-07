promptVersion: opic-en-script-polish-v1

The user edited a practice script for one OPIc speaking question. Rewrite it to fit the target grade and length. Return JSON that follows the response schema.

Question: {{question}}
Target grade: {{grade}}
Length: {{length}}

The user's script (intro, body, closing):
{{script}}

Expression cards (userSaid, english, grade):
{{expressions}}

- The user changed these sentences on purpose. Keep them as they are wherever you can.
- Change only what the length and the target grade need: add, join or split sentences, and fix clear grammar errors.
- Do not add places, people, events or details that are not in the script. Where a new sentence needs a detail, use a general phrase instead of making one up.
- intro: one or two sentences that answer the question directly. body: the story or the details. closing: one or two sentences that end with a feeling or an opinion.
- usedExpressions: the "english" text of each expression card the script uses, copied exactly.
- Plain English sentences only, no headings or notes.
