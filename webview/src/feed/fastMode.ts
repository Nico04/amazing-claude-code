import type { Dict } from '../i18n'
import type { ModelInfo } from '../protocol'

/**
 * Fast mode: Opus at up to two and a half times the speed, paid from usage credits (`/fast` in a terminal).
 *
 * Two halves that come from two places, and the screen needs both. Whether the conversation ASKS for it is
 * the IDE's to say - nothing in the stream says it (see ClaudeSession.fastMode and the `fastMode` message).
 * Whether it is ON the CLI says itself, at the start and the end of every turn (`fast_mode_state` and
 * `fast_mode_disabled_reason` on `system/init` and `result`): it needs usage credits, an organization that
 * allows it and a model that has it, and none of that is ours to know. One function reads the two together,
 * so the desk's menu and chip and the phone's sheet cannot tell different stories.
 */

/**
 * The model menu's entry for fast mode - a switch rather than a model. A sentinel prefixed so that it can never be
 * a model's name, the way the menu's way out to "Add a model" is (see ADD_MODEL in catalog.ts).
 */
export const FAST_MODE = 'acc:fast-mode'

/**
 * The mark fast mode wears - on the model chip, in the menu, on the phone's chip. A text glyph rather than the
 * emoji: the emoji paints itself yellow, and the mark has to take the colour of what it says (lit, dim, warning).
 */
export const FAST_MARK = '↯'

/** `/fast`, `/fast on` and `/fast off` - what each asks for, given what is asked now (see runLocal in App). */
export const fastAsked = (argument: string, now: boolean | undefined): boolean => {
  const word = argument.trim().toLowerCase()
  if (word === 'on') return true
  if (word === 'off') return false
  return !now
}

/**
 * The argument of a `/fast` typed as a whole message - '' for a bare one - or null for anything else. The phone
 * answers it itself, as the panel does (see localCommand): the CLI's own refuses under a stream.
 */
export const fastCommand = (text: string): string | null => {
  const match = /^\/fast(?:\s+(on|off))?\s*$/i.exec(text.trim())
  return match ? (match[1] ?? '') : null
}

/** What the CLI said at the last turn's start or end. */
export interface FastModeReport {
  state: 'on' | 'off' | 'cooldown'
  /** Why it cannot serve - the CLI's own word, absent when nothing blocks it. */
  reason?: string
}

/**
 * The CLI's reason that means "the host has not asked" rather than "it cannot": under the stream it answers
 * this until the panel's ask has reached it. Never news to show.
 */
export const NOT_ASKED = 'sdk_opt_in_required'

/** And the one that means "still finding out" - a moment's state, not an answer. */
export const CHECKING = 'pending'

/**
 * What the screen shows for fast mode:
 * - `off` - not asked for;
 * - `waiting` - asked for, and the CLI has not answered since (it does at the next turn's start);
 * - `on`, `cooldown` - asked for and serving, or paused after a rate limit until it comes back by itself;
 * - `unavailable` - asked for, and the CLI says why it cannot.
 */
export type FastModeShown =
  | { kind: 'off' }
  | { kind: 'waiting' }
  | { kind: 'on' }
  | { kind: 'cooldown' }
  | { kind: 'unavailable'; reason: string }

export const fastModeShown = (asked: boolean | undefined, report: FastModeReport | undefined): FastModeShown => {
  if (!asked) return { kind: 'off' }
  if (!report) return { kind: 'waiting' }
  if (report.state === 'on') return { kind: 'on' }
  if (report.state === 'cooldown') return { kind: 'cooldown' }

  const reason = report.reason
  return !reason || reason === NOT_ASKED || reason === CHECKING ? { kind: 'waiting' } : { kind: 'unavailable', reason }
}

/**
 * Whether a model has fast mode - by the catalogue's own mark (`supportsFastMode`, sent as `fast`), and only
 * without a catalogue by the name, the way the CLI itself decides for a model it has no entry for (Opus 4.8
 * and the Opus 5 family).
 *
 * The menu offers the switch only where it means something: on Sonnet it would be a switch that does
 * nothing, and the docs say a model without it turns it off (see ClaudeSessions.setModel).
 */
export const modelHasFastMode = (models: ModelInfo[] | null, model: string | undefined): boolean => {
  if (!model) return false

  if (models && models.length > 0) {
    const exact = models.find((option) => option.value === model)
    if (exact) return exact.fast === true

    const bare = unmarked(model)
    const resolved = models.find((option) => option.resolved && unmarked(option.resolved) === bare)
    if (resolved) return resolved.fast === true
  }

  return byName(model)
}

const unmarked = (model: string): string => model.replace(/\[[^\]]*\]$/, '')

const byName = (model: string): boolean => {
  const name = unmarked(model).toLowerCase()
  return name === 'opus' || name.includes('opus-4-8') || name.includes('opus-5')
}

/** Why fast mode cannot serve, in the panel's words - the CLI's own word when the panel has none for it. */
export const fastReasonText = (t: Dict, reason: string): string => {
  const words = t.fast.reasons
  switch (reason) {
    case 'extra_usage_disabled':
      return words.extraUsageDisabled
    case 'free':
      return words.free
    case 'preference':
      return words.preference
    case 'network_error':
      return words.networkError
    case 'not_first_party':
      return words.notFirstParty
    case 'disabled_by_env':
      return words.disabledByEnv
    case 'model_not_allowed':
      return words.modelNotAllowed
    default:
      return words.unknown
  }
}

/** The line under the switch in the model menu. */
export const fastModeSub = (t: Dict, shown: FastModeShown): string => {
  switch (shown.kind) {
    case 'off':
      return t.fast.offSub
    case 'waiting':
      return t.fast.waitingSub
    case 'on':
      return t.fast.onSub
    case 'cooldown':
      return t.fast.cooldownSub
    case 'unavailable':
      return t.fast.unavailableSub(fastReasonText(t, shown.reason))
  }
}
