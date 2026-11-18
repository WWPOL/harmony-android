# Functional and stateful fuzzing of the chat contracts

The app's behaviour is checked against the instance it is talking to, not against a mock. The
fuzzer drives a **real instance** with **Hypothesis's stateful testing**, the standard
property-based engine for Python: it generates sequences of operations from a seed, replays
them deterministically, and shrinks a failing sequence to the shortest one that still fails.

## What it checks

The harness reads the instance's own rows after every operation and compares them with what a
client is supposed to believe:

| Rule | What a mismatch means |
| --- | --- |
| Every message a client sends appears exactly once, with the text it sent | a duplicate send, a lost send, or text the client changed |
| No message a client did not send exists in the room | a phantom row |
| An edit changes the row in place; the row count does not grow | an edit that duplicated instead of replacing |
| A deleted message is absent from the room's visible history | a delete that only hid the row locally |
| A reaction is present exactly as many times as it was added | a reaction toggle that double-counted |
| A room's newest page is ordered by `created_at`, `id` and holds no duplicate ids | an ordering or duplicate bug in paging |
| A message's author resolves to the profile that sent it | an author-mapping bug |
| Re-reading a page after a change returns the changed rows | a cache that never invalidates |

## What it does not check

- **It is not the app's UI.** It exercises the same HTTP contracts the app's repositories use,
  through the same endpoints, but it does not tap buttons. The UI's own behaviour is covered by
  the Compose tests and the emulator journey, which are separate evidence.
- **It does not fuzz voice media.** LiveKit needs a media server; the call path's room naming
  and access rules are unit-tested instead.
- **It is not a security test.** It uses the fixture accounts' own tokens, so row-level
  security does what it does; it is not trying to bypass it.

## Running it

It needs an instance with throwaway accounts, described by a fixture JSON:

```json
{
  "url": "http://localhost:54480",
  "users": {"alice": {"key": "alice", "email": "...", "password": "...", "profileId": "..."}},
  "serverId": "...",
  "conversationId": "..."
}
```

`users` needs at least `alice`; every message the harness writes is hers. `serverId` is a
server she can create channels in, and `conversationId` a direct-message conversation she is
part of. The anon key comes from `--anon-key`, then `NATIVE_ANON_KEY`, then a `stack.env`
beside the fixture.

```sh
python3 -m venv /tmp/fuzz-venv && /tmp/fuzz-venv/bin/pip install hypothesis requests
/tmp/fuzz-venv/bin/python e2e/fuzz/stateful.py --fixture e2e/fixture.json --seed 1 --steps 40
```

The fixture is not committed: it holds accounts and passwords. `e2e/.gitignore` covers it.

A failing sequence prints its `@example`-style reproduction: the seed, the ordered action
trace, and the mismatch. `--seed` and `--steps` reproduce it exactly; Hypothesis shrinks the
sequence before it reports, so the trace is the shortest one that still fails.
