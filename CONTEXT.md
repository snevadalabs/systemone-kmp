# Context

The vocabulary this project uses for the System One API. One definition per term, aligned
with the TypeSafe documentation unless the entry says otherwise. This file is a glossary: no spec, no design
notes.

## answer

The typed value a System One model returns for one question, published under that question's id. The
TypeSafe docs use *answer* only for the three server-produced primitives. Our `Answer` type also has an
`UnknownAnswer` variant, which the docs do not describe: it stands for an answer whose primitive this SDK
version does not model, and it is what lets a server-side addition degrade instead of crashing a shipped
client.

## calibration

The property that a probability matches observed frequency across many predictions: outcomes assigned a
probability of 0.8 occur about 80% of the time. TypeSafe trains for it. It is a claim about a group of
predictions, never a guarantee about one answer, and it is not a per-answer field.

## choice

The primitive whose answer is one option from a caller-supplied set, together with a probability for every
option and a confidence. Question type `choice`.

## confidence

A number from 0 to 1, derived from an answer's probability distribution, that summarises how peaked that
distribution is. Present on `choice` and `score` answers; a `noul` answer has none. It is not itself a
probability — it is the single number you threshold on so you do not have to read the distribution.

## legend

On a `score` answer, the caller's levels repeated by number, so the returned position can be read against the
labels that defined it. Present only on `score`.

## noul

The primitive whose answer is a single number from 0 to 1: the probability that the question is true. It has
no probability distribution and no confidence. A noul of 0.5 means yes and no are equally likely; it is not a
medium position on a scale. Question type `noul`.

## primitive

One of the three typed building blocks the API understands: `choice`, `score`, `noul`. It is also the value
of the `type` field on the wire. The TypeSafe docs also call the *answers* primitives; in our vocabulary the
word names the question/answer family, and the difference is one of emphasis only.

## probability

A single number from 0 to 1 for how likely one outcome is. A `probabilities` map is the distribution over the
caller's options or levels — the raw signal. `confidence` summarises that map; it does not replace it.

## question

One judgment asked of the model about a state: an id, a `type`, `instructions`, and — for `choice` and
`score` — `criteria`, the options or levels. Every question in one call sees the same state and is evaluated
independently of the others. In the TypeSafe docs the id is the key of the request's `questions` map. In this
SDK the id is carried inside the question, because the question object is itself the key for typed access —
see *typed question key*.

## score

The primitive whose answer is a position on an ordered set of caller-supplied levels, together with a
probability for every level and a confidence. The position can fall between two levels. Question type
`score`.

## state

The content being evaluated: a string, a JSON object, or an array of text. One state per request. Every
question in that request sees it, and nothing else is context for those questions.

## System One

The class of models that return typed decisions and calibrated probabilities instead of generated text. Jev is
TypeSafe's System One model and d1 is Liquid AI's; the category has several vendors. The name comes from
Kahneman's fast System 1 thinking. It is not a model name and not a mode you select. The wire path is
`/v1/systemone`, under a provider's route prefix — `/decisions` for Liquid.

## provider profile

The `SystemOneProvider` value that names one System One deployment: its origin, its route prefix, the model a
call uses when it names none, and the fingerprint headers it expects. The presets are
`SystemOneProvider.TypeSafe` and `SystemOneProvider.Liquid`; any other value describes a compatible server. It
is configuration, not protocol: the wire contract is the same for every provider, and the SDK reads no process
environment to complete it.

## typed question key

A question object that is both the request's question and the statically typed key used to read that
question's answer. It is specific to this SDK. The TypeSafe docs and both reference SDKs use a plain string
id in a separate container map; our questions carry their own id precisely so that one value can serve as
both.
