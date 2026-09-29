import {
  ProviderTimeoutError,
  ProviderUnavailableError,
  RemoteAdmissionUnavailableError
} from "../errors.js";

const MAX_NETWORK_DISPATCHES = 2;

// Only network failures are wrapped: admission errors must propagate unchanged.
export class ProviderDispatchError extends Error {
  constructor(providerError) {
    super("Provider dispatch failed.");
    this.providerError = providerError;
  }
}

/** One instance per logical operation, shared by every attempt classification. */
export class SharedAttemptExecutor {
  #dispatch;
  #beforeProviderAttempt;
  #now;
  #deadline;
  #controller = new AbortController();
  #timer;
  #callerSignal;
  #onCallerAbort;
  #dispatches = 0;
  #inFlight = false;
  #closed = false;

  constructor({ dispatch, beforeProviderAttempt, operationBudgetMs, signal, now = () => performance.now() }) {
    if (typeof beforeProviderAttempt !== "function") throw new RemoteAdmissionUnavailableError();
    if (typeof dispatch !== "function" || !Number.isFinite(operationBudgetMs) || operationBudgetMs <= 0) {
      throw new ProviderUnavailableError();
    }
    this.#dispatch = dispatch;
    this.#beforeProviderAttempt = beforeProviderAttempt;
    this.#now = now;
    this.#deadline = now() + operationBudgetMs;
    this.#callerSignal = signal;
    this.#onCallerAbort = () => this.#controller.abort();
    if (signal?.aborted) this.#controller.abort();
    else signal?.addEventListener("abort", this.#onCallerAbort, { once: true });
    // Remain referenced while dispatch/authorization is pending under Node 22.
    this.#timer = setTimeout(() => this.#controller.abort(), operationBudgetMs);
  }

  get hasAttemptRemaining() {
    return !this.#closed && this.#dispatches < MAX_NETWORK_DISPATCHES;
  }

  // Read-only observation for internal per-attempt accounting; no control changes.
  get dispatchCount() {
    return this.#dispatches;
  }

  assertActive() {
    const remaining = this.#deadline - this.#now();
    if (this.#controller.signal.aborted || remaining <= 0) {
      this.#controller.abort();
      throw new ProviderTimeoutError();
    }
    if (this.#closed) throw new ProviderUnavailableError();
    return remaining;
  }

  async execute(preparedRequest) {
    this.assertActive();
    if (!this.hasAttemptRemaining || this.#inFlight) throw new ProviderUnavailableError();
    this.#inFlight = true;
    try {
      await this.#beforeProviderAttempt();
      // Durable accounting is intentionally retained if this check fails.
      const remaining = this.assertActive();
      // Integer adapter timeouts must never extend the shared deadline.
      const dispatchTimeoutMs = Math.floor(remaining);
      if (dispatchTimeoutMs <= 0) {
        this.#controller.abort();
        throw new ProviderTimeoutError();
      }
      this.#dispatches += 1;
      let completion;
      try {
        // No await or asynchronous work between the final check and invocation.
        completion = await this.#dispatch(preparedRequest, {
          timeout: dispatchTimeoutMs,
          signal: this.#controller.signal
        });
      } catch (error) {
        this.assertActive();
        throw new ProviderDispatchError(error);
      }
      this.assertActive();
      return completion;
    } finally {
      this.#inFlight = false;
    }
  }

  close() {
    this.#closed = true;
    this.#controller.abort();
    clearTimeout(this.#timer);
    this.#callerSignal?.removeEventListener("abort", this.#onCallerAbort);
  }
}
