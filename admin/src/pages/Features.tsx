import { useQuery, useQueryClient } from "@tanstack/react-query";
import { actions, queries } from "../api/queries";
import type { Features } from "../api/types";
import { useConfirm, useToast } from "../components/feedback";
import { Badge, Card, ErrorState, LoadingCards, PageHead } from "../components/ui";

interface Switch {
  key: keyof Features;
  label: string;
  /** As it reads in "Turn ... on?". */
  name: string;
  hint: string;
  turnOn: string;
  turnOff: string;
  /** The toast after saving, on and off. */
  saved: [string, string];
}

const switches: Switch[] = [
  {
    key: "christmas",
    label: "Christmas theme",
    name: "the Christmas theme",
    hint: "Turns the festive look on for every app.",
    turnOn: "Every app, for every couple, switches to the festive look within about a minute.",
    turnOff: "Every app goes back to the usual look, within about a minute.",
    saved: ["Christmas theme is on.", "Christmas theme is off."],
  },
  {
    key: "bachsInApp",
    label: "Card payments in the app",
    name: "card payments in the app",
    hint:
      "Shows Pay by card (Bachs) on the Android app's Plus screen, everywhere. In the US, the UK, the EU, Iceland, Liechtenstein and " +
      "Norway, Google Play's own sheet offers card too, reported to Google. Elsewhere Google Play's payments policy doesn't allow it, so off is the safe setting there.",
    turnOn:
      "Everyone sees Pay by card on the Plus screen, from app version 0.2.22. Payments made through Google's choice screen are reported to Google within 24 hours.",
    turnOff: "The app offers Google Play only. Card subscriptions already running carry on, and so do their reports to Google.",
    saved: ["Card payments in the app are on.", "Card payments in the app are off."],
  },
  {
    key: "aiDareIdeas",
    label: "Dare ideas",
    name: "dare ideas",
    hint: '"Suggest a dare" on the New dare screen: Claude offers three dares from the partner\'s recent dares and streak. ' + "Up to 10 a day per person.",
    turnOn: '"Suggest a dare" comes back on the New dare screen in every app.',
    turnOff: '"Suggest a dare" disappears from the New dare screen. Setting dares by hand works as always.',
    saved: ["Dare ideas are on.", "Dare ideas are off."],
  },
  {
    key: "aiCoach",
    label: "After-workout coach",
    name: "the after-workout coach",
    hint: "A short note from Claude on each finished dare: pace, what the camera noticed, the streak, one tip. Written once per dare.",
    turnOn: "Finished dares get a coach's note again, on the Heart complete screen and the partner's Today.",
    turnOff: "No new coach's notes, and the ones already written stop showing.",
    saved: ["The after-workout coach is on.", "The after-workout coach is off."],
  },
  {
    key: "aiWeeklyRecap",
    label: "Weekly recap",
    name: "the weekly recap",
    hint: "Sunday at 18:00 in each couple's time zone: a warm recap of their week at the top of Us, with a notification. Quiet weeks are skipped.",
    turnOn: "Recaps are written again from the next Sunday evening, and this week's shows on Us if it was written.",
    turnOff: "No recaps are written or sent, and the card on Us disappears.",
    saved: ["The weekly recap is on.", "The weekly recap is off."],
  },
  {
    key: "aiPlanner",
    label: "Plan something",
    name: "Plan something",
    hint: "On Us: pick a mood, budget, in or out and maybe a city, and Claude suggests three date ideas and two games. Up to 5 a day per person.",
    turnOn: '"Plan something" comes back on Us in every app.',
    turnOff: '"Plan something" disappears from Us.',
    saved: ["Plan something is on.", "Plan something is off."],
  },
];

export function FeaturesPage() {
  const features = useQuery(queries.features());
  const [ask, dialog] = useConfirm();
  const toast = useToast();
  const client = useQueryClient();

  return (
    <>
      <PageHead title="Features" sub="Switches that change every app without a release. Apps pick a change up within about a minute." />
      {features.error ? (
        <ErrorState error={features.error} retry={() => void features.refetch()} />
      ) : features.isPending ? (
        <LoadingCards count={1} />
      ) : (
        <Card>
          {switches.map((item) => {
            // The AI switches start on, so an older API that doesn't send them reads as on.
            const on = features.data?.[item.key] ?? item.key.startsWith("ai");
            return (
              <div className="list-row feature-row" key={item.key}>
                <div>
                  <b id={`feature-${item.key}`}>{item.label}</b> {on ? <Badge tone="good">On</Badge> : <Badge>Off</Badge>}
                  <div className="small faint" id={`feature-${item.key}-hint`}>
                    {item.hint}
                  </div>
                </div>
                <button
                  type="button"
                  role="switch"
                  className="switch"
                  aria-checked={on}
                  aria-labelledby={`feature-${item.key}`}
                  aria-describedby={`feature-${item.key}-hint`}
                  onClick={() =>
                    ask({
                      title: on ? `Turn ${item.name} off?` : `Turn ${item.name} on?`,
                      body: on ? item.turnOff : item.turnOn,
                      confirmLabel: on ? "Turn off" : "Turn on",
                      run: async () => {
                        const saved = await actions.setFeatures({ [item.key]: !on });
                        client.setQueryData(queries.features().queryKey, saved);
                        toast(saved[item.key] ? item.saved[0] : item.saved[1]);
                      },
                    })
                  }
                >
                  <span className="knob" />
                </button>
              </div>
            );
          })}
        </Card>
      )}
      {dialog}
    </>
  );
}
