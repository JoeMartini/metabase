import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import type { OidcAuthProvider } from "metabase-types/api";

import { OidcButton, createOidcAuthProvider } from "./OidcButton";

const mockProvider: OidcAuthProvider = {
  type: "oidc",
  key: "okta",
  "login-prompt": "Sign in with Okta",
  "sso-url": "/auth/sso/okta",
};

describe("OidcButton", () => {
  const originalLocation = window.location;

  beforeEach(() => {
    // @ts-expect-error - overriding window.location for test
    delete window.location;
    window.location = { href: "" } as Location;
  });

  afterEach(() => {
    window.location = originalLocation;
  });

  it("should render with login prompt text", () => {
    render(<OidcButton provider={mockProvider} />);
    expect(screen.getByText("Sign in with Okta")).toBeInTheDocument();
  });

  it("should redirect to sso-url without redirect param when none provided", async () => {
    render(<OidcButton provider={mockProvider} />);
    await userEvent.click(screen.getByText("Sign in with Okta"));
    expect(window.location.href).toBe("/auth/sso/okta");
  });

  it("should redirect to sso-url with redirect param when provided", async () => {
    render(<OidcButton provider={mockProvider} redirectUrl="/dashboard/1" />);
    await userEvent.click(screen.getByText("Sign in with Okta"));
    expect(window.location.href).toBe(
      "/auth/sso/okta?redirect=%2Fdashboard%2F1",
    );
  });
});

describe("createOidcAuthProvider", () => {
  it("should create an AuthProvider with the correct name", () => {
    const authProvider = createOidcAuthProvider(mockProvider);
    expect(authProvider.name).toBe("oidc-okta");
  });

  it("should render OidcButton when Button is called", () => {
    const authProvider = createOidcAuthProvider(mockProvider);
    const { container } = render(
      <authProvider.Button redirectUrl="/home" isCard />,
    );
    expect(container).toHaveTextContent("Sign in with Okta");
  });
});
