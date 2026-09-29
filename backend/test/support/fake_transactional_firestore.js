import { Timestamp } from "firebase-admin/firestore";

function clone(value) {
  if (value instanceof Timestamp) {
    return new Timestamp(value.seconds, value.nanoseconds);
  }
  if (Array.isArray(value)) return value.map(clone);
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, clone(item)]));
  }
  return value;
}

function referencePath(reference) {
  if (
    reference === null ||
    typeof reference !== "object" ||
    typeof reference.collectionName !== "string" ||
    typeof reference.id !== "string"
  ) {
    throw new Error("Invalid fake Firestore document reference.");
  }
  return `${reference.collectionName}/${reference.id}`;
}

function snapshot(value) {
  return Object.freeze({
    exists: value !== undefined,
    data() {
      return value === undefined ? undefined : clone(value);
    }
  });
}

export class FakeTransactionalFirestore {
  #commitFailure = null;
  #documents = new Map();
  #queue = Promise.resolve();
  #retryTransaction = false;
  #transactionFailure = null;

  collection(collectionName) {
    return Object.freeze({
      doc: (id) => Object.freeze({ collectionName, id })
    });
  }

  failNextTransaction(error) {
    this.#transactionFailure = error;
  }

  failNextCommit(error) {
    this.#commitFailure = error;
  }

  retryNextTransaction() {
    this.#retryTransaction = true;
  }

  async runTransaction(operation) {
    const previous = this.#queue;
    let release;
    this.#queue = new Promise((resolve) => {
      release = resolve;
    });
    await previous;

    try {
      if (this.#transactionFailure) {
        const error = this.#transactionFailure;
        this.#transactionFailure = null;
        throw error;
      }

      const executeAttempt = async () => {
        let writeStarted = false;
        const staged = new Map();
        const transaction = Object.freeze({
          get: async (reference) => {
            if (writeStarted) throw new Error("Firestore transaction read after write.");
            const path = referencePath(reference);
            return snapshot(this.#documents.get(path));
          },
          set: (reference, value) => {
            writeStarted = true;
            staged.set(referencePath(reference), clone(value));
          }
        });
        return { result: await operation(transaction), staged };
      };

      if (this.#retryTransaction) {
        this.#retryTransaction = false;
        await executeAttempt();
      }
      const { result, staged } = await executeAttempt();
      if (this.#commitFailure) {
        const error = this.#commitFailure;
        this.#commitFailure = null;
        throw error;
      }
      for (const [path, value] of staged) this.#documents.set(path, value);
      return result;
    } finally {
      release();
    }
  }

  documents(collectionName) {
    const prefix = `${collectionName}/`;
    return [...this.#documents.entries()]
      .filter(([path]) => path.startsWith(prefix))
      .map(([path, value]) => ({
        id: path.slice(prefix.length),
        data: clone(value)
      }));
  }

  getDocument(collectionName, id) {
    const value = this.#documents.get(`${collectionName}/${id}`);
    return value === undefined ? null : clone(value);
  }

  setDocument(collectionName, id, value) {
    this.#documents.set(`${collectionName}/${id}`, clone(value));
  }

  deleteDocument(collectionName, id) {
    this.#documents.delete(`${collectionName}/${id}`);
  }
}
