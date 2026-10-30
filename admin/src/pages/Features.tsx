import { useQuery, useQueryClient } from "@tanstack/react-query";
import { actions, queries } from "../api/queries";
import { useConfirm, useToast } from "../components/feedback";
import { Badge, Card, ErrorState, LoadingCards, PageHead } from "../components/ui";

export function FeaturesPage() {
  const features = useQuery(queries.features());
  const [ask, dialog] = useConfirm();
  const toast = useToast();
  const client = useQueryClient();

  const christmas = features.data?.christmas ?? false;

  return (
    <>
      <PageHead title="Features" sub="Switches that change every app without a release. Apps pick a change up within about a minute." />
      {features.error ? (
        <ErrorState error={features.error} retry={() => void features.refetch()} />
      ) : features.isPending ? (
        <LoadingCards count={1} />
      ) : (
        <Card>
          <div className="list-row feature-row">
            <div>
              <b id="feature-christmas">Christmas theme</b> {christmas ? <Badge tone="good">On</Badge> : <Badge>Off</Badge>}
              <div className="small faint" id="feature-christmas-hint">
                Turns the festive look on for every app.
              </div>
            </div>
            <button
              type="button"
              role="switch"
              className="switch"
              aria-checked={christmas}
              aria-labelledby="feature-christmas"
              aria-describedby="feature-christmas-hint"
              onClick={() =>
                ask({
                  title: christmas ? "Turn the Christmas theme off?" : "Turn the Christmas theme on?",
                  body: christmas
                    ? "Every app goes back to the usual look, within about a minute."
                    : "Every app, for every couple, switches to the festive look within about a minute.",
                  confirmLabel: christmas ? "Turn off" : "Turn on",
                  run: async () => {
                    const saved = await actions.setFeatures({ christmas: !christmas });
                    client.setQueryData(queries.features().queryKey, saved);
                    toast(saved.christmas ? "Christmas theme is on." : "Christmas theme is off.");
                  },
                })
              }
            >
              <span className="knob" />
            </button>
          </div>
        </Card>
      )}
      {dialog}
    </>
  );
}
