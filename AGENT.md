Key consideration / guidelines
- Ultimate goal is for server side bots to mimic/behave as close as possible to real player/real client (sadly no client source code)
- Bots should reuse/share existing real-player code paths (handlers, pipelines) rather than duplicating logic, extract or make accessible the existing method used by real user rather than writing a parallel implementation. This ensures consistency and correctness.
- Keep methods short and readable, use extract method refactoring, extract low-level logic code if it gets too large, even if nothing else share the code.
- Prefers early exits over nested ifs
- If multiple options/solutions is available with tradeoffs, propose and ask for selection, if multiple options are available but certain solution is obviously objectively superior to you then just proceed and no need to ask

Reference if need to look at client code (open source client, may not be 1:1 to real client but good enough reimplementation)
(up 1 folder)\OpenStory