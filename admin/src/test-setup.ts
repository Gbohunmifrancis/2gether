import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

// Tell React these tests drive updates through act(), as Testing Library does.
(globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;

// jsdom has <dialog> but not its modal methods.
if (typeof HTMLDialogElement !== "undefined" && !HTMLDialogElement.prototype.showModal) {
  HTMLDialogElement.prototype.showModal = function showModal(this: HTMLDialogElement) {
    this.setAttribute("open", "");
  };
  HTMLDialogElement.prototype.close = function close(this: HTMLDialogElement) {
    this.removeAttribute("open");
  };
}

// jsdom doesn't scroll; the router restores scroll on navigation.
window.scrollTo = () => {};

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});
