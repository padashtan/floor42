# Contributing

Thanks for your interest. Floor42 has one maintainer and v1.0.0 is its first public release; please open an issue to discuss a change before writing code.

## License and sign-off

Floor42 is licensed under [Apache-2.0](LICENSE) (ADR-0043). Contributions are accepted under the same license, with
the [Developer Certificate of Origin](https://developercertificate.org/) (DCO): by signing off a commit, you certify
that you wrote it or otherwise have the right to submit it under that license. There is no CLA.

Every commit of a pull request must carry a sign-off line with your real name and the address of the commit's author:

```
Signed-off-by: Ada Lovelace <ada@example.org>
```

`git commit -s` adds it; `git rebase --signoff main` adds it to commits already made.

## Before you open a pull request

- `./mvnw spotless:apply && ./mvnw verify` passes (it needs JDK 25 and `npm ci` in `floor42-web` first), and so does
  `npm test` in `floor42-web`.
- See [`docs/README.md`](docs/README.md) for what to read first, and the "Rules" of [`CLAUDE.md`](CLAUDE.md): written
  for the coding agents, they hold for every contributor.

## Ground rules

- All code, identifiers, comments and documentation are in English.
- The domain module (`floor42-domain`) depends on the JDK only.
- No test may call a paid model or run a real agent; use recorded transcripts.
- A structural decision gets an ADR in `docs/adr/`.
