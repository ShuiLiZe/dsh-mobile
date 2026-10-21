import { readFileSync } from 'node:fs'

// Run only after apksigner verify succeeds; this checks the established release identity.
// fork-only: the fork signs with its own long-lived key, not the upstream release certificate.
const expected = '1a3441ad8cb4791a1c0da07b8ae0aea60b31e4961f6304a0600eedb81cd34ff8'
const lines = readFileSync(0, 'utf8').split(/\r?\n/u).map(line => line.trim())
const signerCounts = lines.filter(line => line.startsWith('Number of signers:'))
const certificates = lines.filter(line => line.includes('certificate SHA-256 digest:'))
const certificatePattern = /^(?:Signer #1|V[234](?:\.1)? Signer:) certificate SHA-256 digest: ([a-f\d]{64})$/iu

if (!lines.includes('Verifies')
  || signerCounts.length !== 1
  || signerCounts[0] !== 'Number of signers: 1'
  || certificates.length === 0
  || certificates.some(line => certificatePattern.exec(line)?.[1]?.toLowerCase() !== expected)) {
  console.error('Android APK must have exactly one signer with the established release certificate.')
  process.exitCode = 1
} else {
  console.log('Android release signing certificate verified.')
}
