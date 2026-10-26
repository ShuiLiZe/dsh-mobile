import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { buildExtensionWorkerArtifacts } from './helpers/extension-worker-build.js'

/**
 * The blocking scenarios must run in a separately supervised process: the main
 * suite runs --pool=threads --maxWorkers=1, and a regression that wedged the
 * parent would otherwise hang the whole suite. Here the vitest test only
 * supervises the runner with a hard timeout and asserts its JSON verdict.
 */
describe('supervised extension worker blocking scenarios', () => {
  for (const lateObservation of [false, true]) it(lateObservation
    ? 'retains the expected timeout when HTTP observation waits for worker exit'
    : 'keeps the parent responsive, times the caller out, terminates and recovers', async () => {
    const artifacts = await buildExtensionWorkerArtifacts()
    const root = await mkdtemp(join(tmpdir(), 'dsh-worker-supervised-'))
    let verdict: { stdout: string; stderr: string; code: number | null; signal: NodeJS.Signals | null; timedOut: boolean }
    try {
      verdict = await new Promise((resolve, reject) => {
        const child = spawn(process.execPath, ['tests/supervised/extension-worker-blocking-runner.mjs', artifacts.runtimeEntry, artifacts.supervisorBundle,
          ...(lateObservation ? ['--late-observation'] : [])], {
          cwd: process.cwd(),
          stdio: ['ignore', 'pipe', 'pipe'],
          env: { ...process.env, TMPDIR: root, TMP: root, TEMP: root },
        })
        let stdout = ''
        let stderr = ''
        child.stdout.on('data', chunk => { stdout += String(chunk) })
        child.stderr.on('data', chunk => { stderr += String(chunk); process.stderr.write(chunk) })
        // Hard supervision: a wedged runner fails the test instead of the suite.
        let timedOut = false
        let childError: Error | undefined
        const guard = setTimeout(() => { timedOut = true; child.kill('SIGKILL') }, 15_000)
        child.once('error', error => { childError = error })
        child.once('close', (code, signal) => {
          clearTimeout(guard)
          if (childError !== undefined) reject(childError)
          else resolve({ stdout, stderr, code, signal, timedOut })
        })
      })
    } finally { await rm(root, { recursive: true, force: true }) }
    console.log(JSON.stringify({ lateObservation, childExit: verdict.code, timedOut: verdict.timedOut, temporaryRootRemoved: !existsSync(root) }))
    expect(existsSync(root)).toBe(false)
    expect(verdict.timedOut).toBe(false)
    expect(verdict.signal).toBeNull()
    expect(verdict.code, verdict.stderr).toBe(0)
    const report = JSON.parse(verdict.stdout) as {
      scenarioA: { responsive: boolean; httpAfterExit: boolean; timeout: boolean; terminated: boolean; recovered: boolean; runtimeRotated: boolean }
      scenarioB: { streamCancelled: boolean; terminated: boolean }
    }
    // Scenario A: synchronously blocking action.
    if (lateObservation) {
      expect(report.scenarioA.httpAfterExit).toBe(true)
      expect(report.scenarioA.responsive).toBe(false)
    } else expect(report.scenarioA.responsive).toBe(true)
    expect(report.scenarioA.timeout).toBe(true)
    expect(report.scenarioA.terminated).toBe(true)
    expect(report.scenarioA.recovered).toBe(true)
    expect(report.scenarioA.runtimeRotated).toBe(true)
    // Scenario B: worker blocked while a stream is open; cancel → grace → terminate.
    expect(report.scenarioB.streamCancelled).toBe(true)
    expect(report.scenarioB.terminated).toBe(true)
  }, 25_000)
})
