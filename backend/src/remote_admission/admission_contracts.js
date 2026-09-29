import { RemoteAdmissionUnavailableError } from "../errors.js";

export const AdmissionState = Object.freeze({
  RESERVED: "RESERVED",
  DISPATCHED: "DISPATCHED",
  SUCCEEDED: "SUCCEEDED",
  FAILED_PRE_DISPATCH: "FAILED_PRE_DISPATCH",
  FAILED_POST_DISPATCH: "FAILED_POST_DISPATCH",
  EXPIRED: "EXPIRED"
});

const TRANSITION_EFFECTS = new Map([
  [`${AdmissionState.RESERVED}\0${AdmissionState.DISPATCHED}`, Object.freeze({
    successfulUse: "HOLD",
    providerAttempt: "CONSUME",
    globalAttempt: "CONSUME",
    requiresLeaseExpiry: false
  })],
  [`${AdmissionState.RESERVED}\0${AdmissionState.FAILED_PRE_DISPATCH}`, Object.freeze({
    successfulUse: "RELEASE",
    providerAttempt: "NONE",
    globalAttempt: "NONE",
    requiresLeaseExpiry: false
  })],
  [`${AdmissionState.RESERVED}\0${AdmissionState.EXPIRED}`, Object.freeze({
    successfulUse: "RELEASE",
    providerAttempt: "NONE",
    globalAttempt: "NONE",
    requiresLeaseExpiry: true
  })],
  [`${AdmissionState.DISPATCHED}\0${AdmissionState.SUCCEEDED}`, Object.freeze({
    successfulUse: "CONSUME",
    providerAttempt: "PRESERVE",
    globalAttempt: "PRESERVE",
    requiresLeaseExpiry: false
  })],
  [`${AdmissionState.DISPATCHED}\0${AdmissionState.FAILED_POST_DISPATCH}`, Object.freeze({
    successfulUse: "RELEASE",
    providerAttempt: "PRESERVE",
    globalAttempt: "PRESERVE",
    requiresLeaseExpiry: false
  })],
  [`${AdmissionState.DISPATCHED}\0${AdmissionState.EXPIRED}`, Object.freeze({
    successfulUse: "RELEASE",
    providerAttempt: "PRESERVE",
    globalAttempt: "PRESERVE",
    requiresLeaseExpiry: true
  })]
]);

function transitionKey(fromState, toState) {
  return `${fromState}\0${toState}`;
}

export function canTransitionAdmissionState(fromState, toState) {
  return TRANSITION_EFFECTS.has(transitionKey(fromState, toState));
}

export function admissionTransitionEffect(fromState, toState) {
  const effect = TRANSITION_EFFECTS.get(transitionKey(fromState, toState));
  if (!effect) throw new RemoteAdmissionUnavailableError();
  return effect;
}
