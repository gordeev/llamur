# Preparing public sources

The source tree contains no model weights, credentials or signing identity.
Local assistant instructions/plans, skill folders, IDE state, toolchains, APKs,
raw phone runs and signing material are excluded by `.gitignore`. Files already
tracked by Git must be explicitly removed from its index; ignore rules alone do
not remove them or erase earlier commits.

Run before sharing sources:

```sh
python3 scripts/check-public-source.py
python3 scripts/check-public-source.py --history
```

The first command checks tracked and unignored new files. The second also checks
reachable Git blobs. Both report paths and reason categories without printing
credential values. They recognise common API token/private-key patterns and
workstation home paths; they cannot prove that arbitrary text contains no secret.
Keep account tokens out of fixtures and use public dummy data only.

The existing private repository's original commit includes assistant files and
old absolute workstation paths. Its history has deliberately not been rewritten.
**Do not push that private history into the public repository.** Export the
current checked files into a fresh directory instead:

```sh
python3 scripts/check-public-source.py --export build/llamur-public-source
```

Use a fresh destination each time. This exports source files with executable
bits, including uncommitted implementation, but no `.git`, ignored local files,
model weights, downloaded upstream checkout or Git remotes. Review that directory
and initialise the public repository there. No commit, remote creation or push
is performed by the exporter. Build preparation fetches the exact upstream and
tools using the checked-in locks; see [build instructions](build.md).

`scripts/export-build-source.py` serves a different purpose: a full offline build
snapshot with pristine pinned upstream included and its tree verified. Upstream's
own public contributor instructions can be present in that offline snapshot; the
public Git source export above excludes the downloaded checkout altogether.
