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
      "Offers paying by card (Bachs) next to Google Play in the Android app, in the US, the UK, the EU, Iceland, Liechtenstein and Norway. " +
      "Keep it off until alternative billing is turned on for the app in Play Console and the app release that shows Google's choice screen is live.",
    turnOn: "People in those countries see card payment next to Google Play on the Plus screen. Each payment is reported to Google within 24 hours.",
    turnOff: "The app offers Google Play only. Card subscriptions already running carry on, and so do their reports to Google.",
    saved: ["Card payments in the app are on.", "Card payments in the app are off."],
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
            const on = features.data?.[item.key] ?? false;
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
