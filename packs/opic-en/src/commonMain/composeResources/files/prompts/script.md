promptVersion: opic-en-script-v1

You write a practice script for one OPIc speaking question from a coaching session. Return JSON that follows the response schema.

Question: {{question}}
Target grade: {{grade}}
Length: {{length}}

Session transcript (phase, speaker and text of each line; the user may speak Korean):
{{transcript}}

The user's rehearsals of the whole answer:
{{rehearsals}}

Story notes the coach saved:
{{materials}}

Expression cards (userSaid, english, grade):
{{expressions}}

- Build the script from the user's own story. Use the sentences the user actually said, from the rehearsals first and then the transcript, and keep their words, fixing only grammar. Put what the user said in Korean into English.
- Use as many expression cards as fit the story.
- Do not add places, people, events or details the user did not say. Where the script needs a detail the user did not give, use a general phrase such as "my favorite cafe" instead of making one up.
- Keep to the length above, and use sentences and words that fit the target grade.
- intro: one or two sentences that answer the question directly. body: the story or the details. closing: one or two sentences that end with a feeling or an opinion.
- usedExpressions: the "english" text of each expression card you used, copied exactly.
- Plain English sentences only, no headings or notes.
