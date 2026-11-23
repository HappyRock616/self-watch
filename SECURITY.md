# Security policy

Self Watch keeps health data on the phone. We take reports about anything that could expose it
seriously.

## Reporting a vulnerability

Please report security problems **privately**. Do not open a public issue or pull request for them.

- Contact: TBD by maintainer.
- Until a contact is published, use the code host's private vulnerability reporting feature for this
  repository, if it is enabled.

Include what you found, which version or commit, how to reproduce it, and what an attacker could do
with it. We will acknowledge the report, keep you informed, and credit you in the release notes if you
want.

## Scope

In scope: the Self Watch app, the `link-client` library, the build and release pipeline, and the
food database shipped with the app.

Examples: health data leaving the phone, another app reading Self Watch data without permission,
the link service accepting an unapproved caller, a crash or code execution from a crafted file or
watch message, a release APK that differs from its source.

## Supported versions

Only the latest release gets security fixes.
