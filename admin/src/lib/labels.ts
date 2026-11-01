import type { JourneyKind, LiveEvent, LiveKind } from "../api/types";
import { countryName } from "./countries";
import { humanize, productLabel } from "./format";

/* Words for things the API sends as camelCase names: exercises, games, live events, journey events. */

const EXERCISES: Record<string, string> = {
  squat: "Squats",
  pushUp: "Push-ups",
  jumpingJack: "Jumping jacks",
  plank: "Plank",
  armRaise: "Arm raises",
  highKnees: "High knees",
  sitUp: "Sit-ups",
  lunge: "Lunges",
  gluteBridge: "Glute bridges",
  mountainClimber: "Mountain climbers",
  burpee: "Burpees",
  wallSit: "Wall sit",
  sidePlank: "Side plank",
};
export const exerciseLabel = (name: string) => EXERCISES[name] ?? humanize(name);

const GAMES: Record<string, string> = {
  guessMyAnswer: "Guess my answer",
  coupleQuiz: "Couple quiz",
  memoryMatch: "Memory match",
  wordDash: "Word dash",
  handwriting: "Handwriting",
  truthOrDare: "Truth or dare",
  iCallOn: "I call on",
  wouldYouRather: "Would you rather",
  ticTacToe: "Tic-tac-toe",
  connectFour: "Connect four",
  trivia: "Trivia",
  skillDuel: "Heart to heart duel",
  spotTheDifference: "Spot the difference",
  snakesAndLadders: "Snakes and ladders",
};
export const gameLabel = (name: string) => GAMES[name] ?? humanize(name);

/** Skill kinds travel as the app's PascalCase names; these are the names the app shows. */
const SKILLS: Record<string, string> = {
  FairShare: "Fair share",
  FillTheHeart: "Fill the heart",
  OtherHalf: "Other half",
  Heartbeat: "Heartbeat",
  KissTheCenter: "Kiss the center",
  PromiseRing: "Promise ring",
  CupidsAim: "Cupid's aim",
  SlowKiss: "Slow kiss",
  DrawAHeart: "Draw a heart",
};
/** "FillTheHeart" → "Fill the heart"; a kind this dashboard doesn't know yet is spaced out from its name. */
export const skillLabel = (name: string) => SKILLS[name] ?? humanize(name);

const PROBES: Record<string, string> = { database: "Database", storage: "Photo storage", push: "Push notifications", email: "Email", foodModel: "Food scans" };
export const probeLabel = (name: string) => PROBES[name] ?? humanize(name);

export const LIVE_KIND_LABELS: Record<LiveKind, string> = {
  signedUp: "Sign-ups",
  coupleLinked: "Couples linked",
  coupleEnded: "Couples unlinked",
  dareCompleted: "Dares done",
  dareMissed: "Dares missed",
  gameFinished: "Games",
  plusStarted: "Plus started",
  plusEnded: "Plus ended",
  accountDeleted: "Accounts deleted",
  alert: "Alerts",
};

const someone = (name: string | null) => name ?? "Someone";
const pair = (event: LiveEvent) => (event.partnerName ? `${someone(event.displayName)} & ${event.partnerName}` : someone(event.displayName));

/** One line for a live event, and a smaller one under it. No content: names, exercises, games, products only. */
export function liveLine(event: LiveEvent): { title: string; sub: string } {
  switch (event.kind) {
    case "signedUp":
      return { title: `${someone(event.displayName)} joined`, sub: event.detail ? `from ${countryName(event.detail)}` : "a new account" };
    case "coupleLinked":
      return { title: `${pair(event)} linked up`, sub: "a new couple" };
    case "coupleEnded":
      return { title: `${pair(event)} unlinked`, sub: "the couple ended" };
    case "dareCompleted":
      return {
        title: `${someone(event.displayName)} did the dare`,
        sub: event.detail ? `${exerciseLabel(event.detail)}, set by ${event.partnerName ?? "their partner"}` : "a heart made",
      };
    case "dareMissed":
      return { title: `${someone(event.displayName)} missed a dare`, sub: event.detail ? exerciseLabel(event.detail) : "the alarm ran out" };
    case "gameFinished":
      return { title: `${pair(event)} finished a game`, sub: event.detail ? gameLabel(event.detail) : "a game" };
    case "plusStarted":
      return { title: `${pair(event)} went Plus`, sub: event.detail ? productLabel(event.detail) : "Heartpact Plus" };
    case "plusEnded":
      return { title: `${someone(event.displayName)}'s Plus ended`, sub: event.detail ? productLabel(event.detail) : "" };
    case "accountDeleted":
      return { title: "An account was deleted", sub: "everything of theirs is gone" };
    case "alert":
      return { title: event.detail ?? "Alert", sub: "needs a look" };
  }
}

export const JOURNEY_LABELS: Record<JourneyKind, { one: string; many: string }> = {
  signedUp: { one: "Signed up", many: "Signed up" },
  linked: { one: "Linked with their partner", many: "Linked" },
  unlinked: { one: "Unlinked", many: "Unlinked" },
  active: { one: "Opened the app", many: "Opened the app" },
  dareSet: { one: "dare set", many: "dares set" },
  dareCompleted: { one: "dare done", many: "dares done" },
  dareMissed: { one: "dare missed", many: "dares missed" },
  mealLogged: { one: "meal logged", many: "meals logged" },
  photoSynced: { one: "photo shared", many: "photos shared" },
  questionAnswered: { one: "question answered", many: "questions answered" },
  gameFinished: { one: "game finished", many: "games finished" },
  plusStarted: { one: "Started Plus", many: "Started Plus" },
  plusEnded: { one: "Plus ended", many: "Plus ended" },
  adminAction: { one: "admin action", many: "admin actions" },
};
