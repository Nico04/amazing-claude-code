import { describe, expect, it } from 'vitest'
import { en } from '../i18n/en'
import type { AgentEvent, ModelInfo } from '../protocol'
import { initialPanelState, reducePanel, type PanelState } from './build'
import { fastAsked, fastCommand, fastModeShown, fastReasonText, modelHasFastMode } from './fastMode'
import { localCommand } from './slash'

/**
 * Fast mode takes two halves from two places - the ask from the IDE, whether it serves from the CLI - and every
 * screen reads them through the same few functions. What is pinned here is how they read together.
 */
describe('what fast mode shows', () => {
  it('is off while nobody asks, whatever the CLI says', () => {
    expect(fastModeShown(false, { state: 'off', reason: 'extra_usage_disabled' })).toEqual({ kind: 'off' })
    expect(fastModeShown(undefined, undefined)).toEqual({ kind: 'off' })
  })

  // Under a stream the CLI answers "the host has not asked" until the ask reaches it: that is waiting, not news.
  it('waits for the CLI to hear the ask', () => {
    expect(fastModeShown(true, undefined)).toEqual({ kind: 'waiting' })
    expect(fastModeShown(true, { state: 'off', reason: 'sdk_opt_in_required' })).toEqual({ kind: 'waiting' })
    expect(fastModeShown(true, { state: 'off', reason: 'pending' })).toEqual({ kind: 'waiting' })
  })

  it('serves, pauses, or says why it cannot', () => {
    expect(fastModeShown(true, { state: 'on' })).toEqual({ kind: 'on' })
    expect(fastModeShown(true, { state: 'cooldown' })).toEqual({ kind: 'cooldown' })
    expect(fastModeShown(true, { state: 'off', reason: 'extra_usage_disabled' })).toEqual({
      kind: 'unavailable',
      reason: 'extra_usage_disabled',
    })
  })

  it('words a reason it knows and falls back on one it does not', () => {
    expect(fastReasonText(en, 'extra_usage_disabled')).toBe(en.fast.reasons.extraUsageDisabled)
    expect(fastReasonText(en, 'a_reason_from_a_later_cli')).toBe(en.fast.reasons.unknown)
  })
})

describe('which models have fast mode', () => {
  const catalogue: ModelInfo[] = [
    { value: 'default', label: 'Default', description: '', resolved: 'claude-opus-5-5', fast: true },
    { value: 'opus', label: 'Opus', description: '', resolved: 'claude-opus-5-5', fast: true },
    { value: 'claude-opus-4-7', label: 'Opus 4.7', description: '', resolved: 'claude-opus-4-7' },
    { value: 'sonnet', label: 'Sonnet', description: '', resolved: 'claude-sonnet-5' },
  ]

  it('takes the catalogue at its word, value first', () => {
    expect(modelHasFastMode(catalogue, 'default')).toBe(true)
    expect(modelHasFastMode(catalogue, 'sonnet')).toBe(false)
    // A family's short name has it; a generation of the same family the catalogue marks without it does not.
    expect(modelHasFastMode(catalogue, 'claude-opus-4-7')).toBe(false)
  })

  it('reads a name the menu does not offer by the full names, the window mark aside', () => {
    expect(modelHasFastMode(catalogue, 'claude-opus-5-5[1m]')).toBe(true)
    expect(modelHasFastMode(catalogue, 'claude-opus-4-7[1m]')).toBe(false)
  })

  // The CLI's own rule for a model it has no entry for: Opus 4.8 and the Opus 5 family.
  it('goes by the name only without a catalogue', () => {
    expect(modelHasFastMode(null, 'claude-opus-5-5')).toBe(true)
    expect(modelHasFastMode(null, 'claude-opus-4-8')).toBe(true)
    expect(modelHasFastMode(null, 'claude-opus-4-7')).toBe(false)
    expect(modelHasFastMode(null, 'claude-sonnet-5')).toBe(false)
    expect(modelHasFastMode(null, undefined)).toBe(false)
  })
})

describe('/fast', () => {
  it('is the panel own command, with or without a word after it', () => {
    expect(localCommand(en, '/fast')).toEqual({ name: 'fast', argument: '' })
    expect(localCommand(en, '/fast on')).toEqual({ name: 'fast', argument: 'on' })
  })

  it('asks for what it says, and a bare one turns the switch over', () => {
    expect(fastAsked('on', false)).toBe(true)
    expect(fastAsked('OFF', true)).toBe(false)
    expect(fastAsked('', true)).toBe(false)
    expect(fastAsked('', undefined)).toBe(true)
  })

  // The phone answers it itself; anything more than the command is a message after all.
  it('is recognised on the phone only as a whole message', () => {
    expect(fastCommand(' /fast ')).toBe('')
    expect(fastCommand('/fast off')).toBe('off')
    expect(fastCommand('/fast and tell me why')).toBeNull()
    expect(fastCommand('/faster')).toBeNull()
  })
})

describe('the feed and fast mode', () => {
  const at = 1_700_000_000_000

  const init = (state: 'on' | 'off' | 'cooldown', reason?: string): AgentEvent => ({
    type: 'system',
    subtype: 'init',
    fast_mode_state: state,
    ...(reason ? { fast_mode_disabled_reason: reason } : {}),
  })
  const result = (state: 'on' | 'off', reason?: string): AgentEvent => ({
    type: 'result',
    subtype: 'success',
    num_turns: 1,
    result: 'done',
    fast_mode_state: state,
    ...(reason ? { fast_mode_disabled_reason: reason } : {}),
  })
  const play = (state: PanelState, ...events: AgentEvent[]): PanelState =>
    events.reduce((panel, event, index) => reducePanel(panel, { kind: 'agent', event }, at + index), state)
  const rows = (state: PanelState) => state.items.filter((item) => item.kind === 'fast')
  const asked = reducePanel(initialPanelState, { kind: 'fastModeApplied', on: true }, at)

  it('reads what the CLI says and puts nothing in the feed while it serves', () => {
    const state = play(asked, init('on'), result('on'))

    expect(state.fastReport).toEqual({ state: 'on' })
    expect(fastModeShown(state.fastMode, state.fastReport)).toEqual({ kind: 'on' })
    expect(rows(state)).toEqual([])
  })

  // The CLI repeats the reason at every turn's start and end; the feed says it once since the ask.
  it('says once that it did not turn on, and again only after it is asked anew', () => {
    let state = play(asked, init('off', 'extra_usage_disabled'), result('off', 'extra_usage_disabled'))
    state = play(state, init('off', 'extra_usage_disabled'))
    expect(rows(state)).toEqual([expect.objectContaining({ reason: 'extra_usage_disabled' })])

    state = reducePanel(state, { kind: 'fastModeApplied', on: false }, at)
    state = reducePanel(state, { kind: 'fastModeApplied', on: true }, at)
    state = play(state, init('off', 'extra_usage_disabled'))
    expect(rows(state)).toHaveLength(2)
  })

  it('says nothing of a conversation that never asked', () => {
    const state = play(initialPanelState, init('off', 'sdk_opt_in_required'), result('off', 'extra_usage_disabled'))

    expect(rows(state)).toEqual([])
  })

  // A past conversation's lines are no news about now.
  it('ignores a replay', () => {
    const state = reducePanel(asked, { kind: 'agent', event: init('off', 'extra_usage_disabled'), replay: true }, at)

    expect(state.fastReport).toBeUndefined()
    expect(rows(state)).toEqual([])
  })

  // A subagent's start-up is about its own stream, not this conversation's.
  it('ignores a subagent start-up', () => {
    const state = play(asked, { ...init('off', 'extra_usage_disabled'), task_id: 'agent-1' } as AgentEvent)

    expect(state.fastReport).toBeUndefined()
  })
})
