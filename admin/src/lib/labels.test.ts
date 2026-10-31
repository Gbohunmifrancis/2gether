import { describe, expect, it } from "vitest";
import { SKILLS } from "../demo/world";
import { skillLabel } from "./labels";

describe("labels", () => {
  it("names every skill game the way the app does", () => {
    expect(skillLabel("FillTheHeart")).toBe("Fill the heart");
    expect(skillLabel("CupidsAim")).toBe("Cupid's aim");
    expect(skillLabel("DrawAHeart")).toBe("Draw a heart");
    expect(skillLabel("SlowKiss")).toBe("Slow kiss");
    for (const kind of SKILLS) expect(skillLabel(kind)).toMatch(/^[A-Z][a-z' ]*$/);
  });

  it("spaces out a kind it doesn't know yet", () => {
    expect(skillLabel("PaperPlane")).toBe("Paper plane");
  });
});
