# Security

This SDK sends an API key on every request, so a leak matters more here than in most libraries.

Please do not open a public issue for a security problem. Report it privately through
[GitHub's private vulnerability reporting](https://github.com/snevadalabs/systemone-kmp/security/advisories/new).
Include the version, a reproduction, and what the key or the data could reach; expect an acknowledgement within
a few days.

Never paste a live `TYPESAFE_API_KEY` into an issue, a pull request, or a test fixture. Rotate a key you
believe has been exposed.
