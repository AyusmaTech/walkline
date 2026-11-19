# Security and publication

## Keep private material out of Git

Use your own ignored `backend/.env` and `android/local.properties`. Only the empty example files belong in Git. Signing keys, cloud-account metadata, local assistant/editor state, logs, configured APKs, recordings, and internal notes are excluded.

A configured APK embeds its pilot token and optional Android Maps key. Do not publish a local debug APK as a public release. Android API keys need package/signing-certificate/API restrictions. Server credentials belong only on the server.

Before committing:

```sh
node scripts/check-public-repo.mjs
```

For a full Gitleaks scan of publishable files and existing history:

```sh
node scripts/check-public-repo.mjs --gitleaks gitleaks
```

Install Gitleaks from its [official releases](https://github.com/gitleaks/gitleaks/releases). CI uses a pinned checksum-verified release and runs without project credentials. The local guard also checks staged content, rejects private/generated paths, compares configured local secret values when available, and verifies the Gradle wrapper JAR against its official checksum. The Gradle distribution checksum is pinned separately. Findings omit secret values. These checks reduce risk; review changes before pushing.

If a usable credential is committed, revoke/rotate it first, then remove it from affected files and history. Adding it to `.gitignore` alone does not remove an existing commit.

## Deployment boundary

The shared bearer token is a local pilot access gate, not production user authentication. An APK can be inspected. Before general distribution, use an appropriate authentication design, server-side quotas, HTTPS, and providers sized for the expected usage. The public Photon/FOSSGIS endpoints are not a production availability commitment.

The server validates input, uses bounded requests/caches, returns explicit errors, and does not write queries or private data to application logs. Upstream providers have their own policies.

## Reporting

Do not post credentials, private addresses, delivery instructions, or raw location traces in public issues. Use GitHub private vulnerability reporting if enabled, or arrange a private report with the maintainer. Provide a minimal redacted reproduction.
