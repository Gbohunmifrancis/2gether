"use client";

import {
  FormEvent,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import type React from "react";
import {
  Bell,
  CalendarDays,
  ChevronRight,
  Gamepad2,
  Grid3X3,
  Heart,
  Home,
  LockKeyhole,
  LogOut,
  MapPinned,
  MessageCircle,
  MoreHorizontal,
  Play,
  LetterText,
  Palette,
  Send,
  Settings,
  Sparkles,
  UserRound,
  UserPen,
  Trophy,
  X,
} from "lucide-react";
import {
  acceptGameSession,
  acceptPartnerInvite,
  applyGameAction,
  breakUp,
  claimCycle,
  createCoupleHubConnection,
  createGameSession,
  createPartnerInvite,
  endGameSession,
  getAccessToken,
  getCouple,
  getCycleLogs,
  getCyclePrediction,
  getCycleProfile,
  getDashboardSnapshot,
  getGameSession,
  getGameSessions,
  getLocations,
  getMe,
  getMessages,
  getNotifications,
  getSharedCycle,
  login,
  logout,
  markAllNotificationsRead,
  markNotificationRead,
  register,
  saveCycleLog,
  saveCycleProfile,
  sendMessage,
  updateProfile,
  type AppNotification,
  type AuthUser,
  type Couple,
  type CoupleLocation,
  type CycleLog,
  type CyclePrediction,
  type CycleProfile,
  type DashboardSnapshot,
  type GameCountdown,
  type GameInvitation,
  type GameSession,
  type Gender,
  type Message,
  type PartnerInvite,
  type SharedCycle,
  type TypingState,
} from "@/lib/api";
import { LiveMap } from "./LiveMap";
import { playSound, unlockSounds } from "@/lib/sounds";

const navigation = [
  { label: "Home", icon: Home },
  { label: "Games", icon: Gamepad2 },
  { label: "Cycle", icon: CalendarDays },
  { label: "Messages", icon: MessageCircle },
  { label: "Map", icon: MapPinned },
] as const;
const gameTypes = [
  {
    value: "guessMyAnswer",
    title: "Guess my answer",
    description: "Compare your answers to five prompts.",
    icon: MessageCircle,
  },
  {
    value: "coupleQuiz",
    title: "Couple quiz",
    description: "Take turns answering questions together.",
    icon: Heart,
  },
  {
    value: "memoryMatch",
    title: "Memory match",
    description: "Find all six matching pairs.",
    icon: Grid3X3,
  },
  {
    value: "onGame",
    title: "On Game",
    description: "Race through Animal, Place, Things, and Food.",
    icon: LetterText,
  },
];

export default function HomePage() {
  const [booting, setBooting] = useState(true);
  const [user, setUser] = useState<AuthUser | null>(null);
  const [activeTab, setActiveTab] = useState("Home");
  const [snapshot, setSnapshot] = useState<DashboardSnapshot | null>(null);
  const [couple, setCouple] = useState<Couple | null>(null);
  const [profile, setProfile] = useState<CycleProfile | null>(null);
  const [prediction, setPrediction] = useState<CyclePrediction | null>(null);
  const [sharedCycle, setSharedCycle] = useState<SharedCycle | null>(null);
  const [logs, setLogs] = useState<CycleLog[]>([]);
  const [messages, setMessages] = useState<Message[]>([]);
  const [games, setGames] = useState<GameSession[]>([]);
  const [selectedGameId, setSelectedGameId] = useState<string | null>(null);
  const [invite, setInvite] = useState<PartnerInvite | null>(null);
  const [notifications, setNotifications] = useState<AppNotification[]>([]);
  const [liveNotification, setLiveNotification] =
    useState<AppNotification | null>(null);
  const [notificationsOpen, setNotificationsOpen] = useState(false);
  const [gameInvitation, setGameInvitation] = useState<GameInvitation | null>(
    null,
  );
  const [countdown, setCountdown] = useState<GameCountdown | null>(null);
  const [countdownNow, setCountdownNow] = useState(() => Date.now());
  const [typing, setTyping] = useState<TypingState | null>(null);
  const [locations, setLocations] = useState<CoupleLocation[]>([]);
  const [celebration, setCelebration] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const coupleConnectionRef = useRef<ReturnType<
    typeof createCoupleHubConnection
  > | null>(null);
  const locationWatchRef = useRef<number | null>(null);
  const togetherLocationRef = useRef(false);

  const loadData = useCallback(async (currentUserId?: string) => {
    const [
      dashboard,
      cycleProfile,
      cyclePrediction,
      cycleLogs,
      messageList,
      sessions,
      coupleSpace,
      notificationList,
      shared,
      locationList,
    ] = await Promise.all([
      getDashboardSnapshot(),
      getCycleProfile(),
      getCyclePrediction(),
      getCycleLogs(),
      getMessages(),
      getGameSessions(),
      getCouple().catch(() => null),
      getNotifications(),
      getSharedCycle().catch(() => null),
      getLocations().catch(() => []),
    ]);
    setSnapshot(dashboard);
    setProfile(cycleProfile);
    setPrediction(cyclePrediction);
    setLogs(cycleLogs);
    setMessages(messageList);
    setGames(sessions);
    setCouple(coupleSpace);
    setNotifications(notificationList);
    setSharedCycle(shared);
    setLocations(locationList);
    const activeSession = sessions.find(
      (item) =>
        item.status === "inProgress" || item.status === "waitingForPartner",
    );
    if (activeSession?.status === "inProgress") {
      setActiveTab("Games");
      if (
        activeSession.countdownEndsAtUtc &&
        new Date(activeSession.countdownEndsAtUtc).getTime() > Date.now()
      )
        setCountdown({
          sessionId: activeSession.id,
          countdownEndsAtUtc: activeSession.countdownEndsAtUtc,
        });
    } else if (
      activeSession?.status === "waitingForPartner" &&
      currentUserId &&
      activeSession.invitedByUserId !== currentUserId
    ) {
      setGameInvitation({
        sessionId: activeSession.id,
        gameType: activeSession.gameType,
        invitedByUserId: activeSession.invitedByUserId ?? "",
        invitedByName: dashboard.partnerName,
        createdAtUtc: activeSession.startedAtUtc ?? dashboard.generatedAtUtc,
      });
    }
    setSelectedGameId(
      (current) =>
        current ??
        sessions.find(
          (item) =>
            item.status === "inProgress" || item.status === "waitingForPartner",
        )?.id ??
        null,
    );
  }, []);

  useEffect(() => {
    if (!getAccessToken()) {
      setBooting(false);
      return;
    }
    getMe()
      .then(async (me) => {
        setUser(me);
        await loadData(me.id);
      })
      .catch(() => {
        logout();
        setUser(null);
      })
      .finally(() => setBooting(false));
  }, [loadData]);

  useEffect(() => {
    if (!user) return;
    const connection = createCoupleHubConnection();
    coupleConnectionRef.current = connection;
    const upsertMessage = (message: Message) => {
      setMessages((current) =>
        [...current.filter((item) => item.id !== message.id), message].sort(
          (a, b) => a.createdAtUtc.localeCompare(b.createdAtUtc),
        ),
      );
      setSnapshot((current) =>
        current
          ? {
              ...current,
              latestNote: message.body,
              latestNoteAuthor:
                message.senderUserId === user.id
                  ? user.displayName
                  : current.partnerName,
            }
          : current,
      );
      if (message.senderUserId !== user.id) {
        playSound(message.isPrivate ? "private" : "message");
        notifyBrowser(
          message.isPrivate ? "Private message" : "New love note",
          message.isPrivate
            ? "Your partner sent something just for you."
            : message.body,
        );
      }
    };
    const upsertGame = (game: GameSession) => {
      setGames((current) =>
        current.some((item) => item.id === game.id)
          ? current.map((item) => (item.id === game.id ? game : item))
          : [game, ...current],
      );
      setSelectedGameId(game.id);
    };
    const addNotification = (item: AppNotification) => {
      if (item.userId !== user.id) return;
      setNotifications((current) => [
        item,
        ...current.filter((entry) => entry.id !== item.id),
      ]);
      setLiveNotification(item);
      setSnapshot((current) =>
        current
          ? {
              ...current,
              notificationCount:
                current.notificationCount + (item.readAtUtc ? 0 : 1),
            }
          : current,
      );
      playSound(
        item.type === "gameInvite"
          ? "countdown"
          : item.type === "coupleConnected"
            ? "connect"
            : "message",
      );
      notifyBrowser(item.title, item.body);
    };
    connection.on("MessageCreated", upsertMessage);
    connection.on("MessageUpdated", upsertMessage);
    connection.on("MessageDeleted", (id: string) =>
      setMessages((current) => current.filter((item) => item.id !== id)),
    );
    connection.on("PresenceChanged", (online: boolean) =>
      setSnapshot((current) =>
        current ? { ...current, partnerOnline: online } : current,
      ),
    );
    connection.on("CoupleTogether", () => {
      playSound("together");
      setCelebration("Both of you are here");
    });
    connection.on("CoupleConnected", () => {
      playSound("connect");
      setCelebration("Your private space is connected");
      void loadData(user.id);
    });
    connection.on("CoupleEnded", () => {
      logout();
      setUser(null);
      setCelebration(null);
    });
    connection.on("NotificationCreated", addNotification);
    connection.on("GameInvitation", (item: GameInvitation) => {
      setGameInvitation(item);
      setActiveTab("Games");
      playSound("countdown");
      notifyBrowser(
        "Game invitation",
        `${item.invitedByName} invited you to play.`,
      );
    });
    connection.on("GameCountdown", (item: GameCountdown) => {
      setCountdown(item);
      setSelectedGameId(item.sessionId);
      setActiveTab("Games");
      playSound("countdown");
    });
    connection.on("GameStarted", (item: GameSession) => {
      upsertGame(item);
      setActiveTab("Games");
      setCountdown(
        item.countdownEndsAtUtc
          ? { sessionId: item.id, countdownEndsAtUtc: item.countdownEndsAtUtc }
          : null,
      );
    });
    connection.on("GameStateChanged", upsertGame);
    connection.on("GameCompleted", (item: GameSession) => {
      upsertGame(item);
      playSound("celebrate");
      setCelebration("Round complete");
    });
    connection.on(
      "GameEnded",
      (item: { sessionId: string; endedByName: string }) => {
        setGames((current) =>
          current.map((game) =>
            game.id === item.sessionId
              ? { ...game, status: "abandoned" }
              : game,
          ),
        );
        setCelebration(`${item.endedByName} ended the game`);
      },
    );
    connection.onreconnected(() => {
      void getGameSessions().then((sessions) => {
        setGames(sessions);
        const active = sessions.find((item) => item.status === "inProgress" || item.status === "waitingForPartner");
        if (!active) return;
        setSelectedGameId(active.id);
        if (active.status === "waitingForPartner" && active.invitedByUserId !== user.id)
          setGameInvitation({ sessionId: active.id, gameType: active.gameType, invitedByUserId: active.invitedByUserId ?? "", invitedByName: snapshot?.partnerName ?? "Your partner", createdAtUtc: active.startedAtUtc ?? new Date().toISOString() });
        if (active.countdownEndsAtUtc && new Date(active.countdownEndsAtUtc).getTime() > Date.now())
          setCountdown({ sessionId: active.id, countdownEndsAtUtc: active.countdownEndsAtUtc });
      }).catch(() => undefined);
    });
    connection.on("TypingChanged", (item: TypingState) =>
      setTyping(item.isTyping ? item : null),
    );
    connection.on("LocationUpdated", (item: CoupleLocation) =>
      setLocations((current) => [
        ...current.filter((entry) => entry.userId !== item.userId),
        item,
      ]),
    );
    connection.on("LocationStopped", (userId: string) =>
      setLocations((current) =>
        current.filter((entry) => entry.userId !== userId),
      ),
    );
    void connection.start().catch(() => undefined);
    return () => {
      coupleConnectionRef.current = null;
      void connection.stop();
    };
  }, [loadData, user]);

  useEffect(() => {
    if (!celebration) return;
    const timer = window.setTimeout(() => setCelebration(null), 3200);
    return () => window.clearTimeout(timer);
  }, [celebration]);
  useEffect(() => {
    if (!liveNotification) return;
    const timer = window.setTimeout(() => setLiveNotification(null), 4200);
    return () => window.clearTimeout(timer);
  }, [liveNotification]);
  useEffect(() => {
    if (!countdown) return;
    const timer = window.setInterval(() => {
      const now = Date.now();
      setCountdownNow(now);
      if (new Date(countdown.countdownEndsAtUtc).getTime() <= now) {
        setCountdown(null);
        playSound("celebrate");
      }
    }, 200);
    return () => window.clearInterval(timer);
  }, [countdown]);
  useEffect(() => {
    const together =
      locations.length >= 2 &&
      distanceMeters(locations[0], locations[1]) <
        Math.max(
          100,
          locations[0].accuracyMeters + locations[1].accuracyMeters,
        );
    if (together && !togetherLocationRef.current) {
      playSound("together");
      setCelebration("You found each other");
    }
    togetherLocationRef.current = together;
  }, [locations]);
  useEffect(
    () => () => {
      if (locationWatchRef.current !== null && navigator.geolocation)
        navigator.geolocation.clearWatch(locationWatchRef.current);
    },
    [],
  );

  async function authenticate(
    mode: "login" | "register",
    values: {
      email: string;
      password: string;
      displayName: string;
      dateOfBirth: string;
      gender: Gender;
    },
  ) {
    setError(null);
    unlockSounds();
    try {
      const response =
        mode === "login"
          ? await login(values.email, values.password)
          : await register(values);
      setUser(response.user);
      await loadData(response.user.id);
    } catch (requestError) {
      setError(errorMessage(requestError));
    }
  }
  function signOut() {
    logout();
    setUser(null);
    setSnapshot(null);
    setMessages([]);
    setGames([]);
  }
  async function acceptInvitation() {
    if (!gameInvitation) return;
    try {
      const session = await acceptGameSession(gameInvitation.sessionId);
      replaceGame(setGames, session);
      setSelectedGameId(session.id);
      setCountdown({
        sessionId: session.id,
        countdownEndsAtUtc:
          session.countdownEndsAtUtc ??
          new Date(Date.now() + 5000).toISOString(),
      });
      setGameInvitation(null);
    } catch (requestError) {
      setError(errorMessage(requestError));
    }
  }
  async function publishLocation(position: GeolocationPosition) {
    try {
      const location =
        await coupleConnectionRef.current?.invoke<CoupleLocation>(
          "UpdateLocation",
          position.coords.latitude,
          position.coords.longitude,
          position.coords.accuracy,
        );
      if (location)
        setLocations((current) => [
          ...current.filter((item) => item.userId !== location.userId),
          location,
        ]);
    } catch (requestError) {
      setError(errorMessage(requestError));
    }
  }
  async function shareLocation() {
    if (!navigator.geolocation || !coupleConnectionRef.current) {
      setError("Location sharing is unavailable in this browser.");
      return;
    }
    unlockSounds();
    if (locationWatchRef.current !== null)
      navigator.geolocation.clearWatch(locationWatchRef.current);
    locationWatchRef.current = navigator.geolocation.watchPosition(
      (position) => {
        void publishLocation(position);
      },
      () => setError("Location access was not granted."),
      { enableHighAccuracy: true, maximumAge: 15000, timeout: 15000 },
    );
  }
  async function stopLocation() {
    try {
      if (locationWatchRef.current !== null && navigator.geolocation) {
        navigator.geolocation.clearWatch(locationWatchRef.current);
        locationWatchRef.current = null;
      }
      await coupleConnectionRef.current?.invoke("StopLocationSharing");
      setLocations((current) =>
        current.filter((item) => item.userId !== user?.id),
      );
    } catch (requestError) {
      setError(errorMessage(requestError));
    }
  }

  if (booting) return <StateScreen text="Opening your private space..." />;
  if (!user) return <AuthScreen error={error} onSubmit={authenticate} />;
  if (!snapshot) return <StateScreen text="Loading your space together..." />;
  const selectedGame = games.find((item) => item.id === selectedGameId) ?? null;
  const isLinked = couple?.status === "active";
  const countdownSeconds = countdown
    ? Math.max(
        0,
        Math.ceil(
          (new Date(countdown.countdownEndsAtUtc).getTime() - countdownNow) /
            1000,
        ),
      )
    : 0;
  const unreadCount = notifications.filter((item) => !item.readAtUtc).length;

  return (
    <main className="app-shell" onPointerDown={unlockSounds}>
      {liveNotification && (
        <button
          className="live-notification"
          onClick={() => {
            setNotificationsOpen(true);
            setLiveNotification(null);
          }}
        >
          <Bell size={16} />
          <span>
            <strong>{liveNotification.title}</strong>
            <small>{liveNotification.body}</small>
          </span>
          <X size={14} />
        </button>
      )}
      {celebration && (
        <div className="celebration" role="status">
          <Heart fill="currentColor" size={34} />
          <strong>{celebration}</strong>
        </div>
      )}
      {gameInvitation && (
        <GameInvitationModal
          invitation={gameInvitation}
          onAccept={() => void acceptInvitation()}
          onDismiss={() => setGameInvitation(null)}
        />
      )}
      <aside className="desktop-sidebar">
        <Brand />
        <div className="sidebar-couple-card">
          <div className="avatar-pair">
            <span>{initial(snapshot.currentUserName)}</span>
            {isLinked && <span>{initial(snapshot.partnerName)}</span>}
          </div>
          <div>
            <strong>
              {isLinked
                ? `${snapshot.currentUserName} & ${snapshot.partnerName}`
                : "Waiting for partner"}
            </strong>
            <small>
              {isLinked
                ? "your private space"
                : "Invite your partner to connect"}
            </small>
          </div>
          <MoreHorizontal size={17} />
        </div>
        <nav className="side-nav">
          {navigation.map(({ label, icon: Icon }) => (
            <button
              className={activeTab === label ? "active" : ""}
              key={label}
              onClick={() => setActiveTab(label)}
            >
              <Icon size={18} />
              {label}
            </button>
          ))}
          <button
            className={activeTab === "Profile" ? "active" : ""}
            onClick={() => setActiveTab("Profile")}
          >
            <UserRound size={18} />
            Profile
          </button>
        </nav>
        <div className="sidebar-footer">
          <button onClick={() => setActiveTab("Settings")}>
            <Settings size={17} />
            Settings
          </button>
          <button onClick={signOut}>
            <LogOut size={17} />
            Sign out
          </button>
          <div className="private-badge">
            <LockKeyhole size={16} />
            <span>Just for the two of you.</span>
          </div>
        </div>
      </aside>
      <section className="page-column">
        <header className="topbar">
          <div className="mobile-brand">
            <Brand compact />
          </div>
          <div className="presence">
            <span
              className={`online-dot ${isLinked && snapshot.partnerOnline ? "" : "offline"}`}
            />
            {isLinked
              ? snapshot.partnerOnline
                ? `${snapshot.partnerName} is online`
                : `${snapshot.partnerName} is away`
              : "Waiting for partner"}
          </div>
          <div className="notification-wrap">
            <button
              className="icon-button notification"
              aria-label="Notifications"
              onClick={() => setNotificationsOpen((open) => !open)}
            >
              <Bell size={19} />
              {unreadCount > 0 && <span>{Math.min(unreadCount, 99)}</span>}
            </button>
            {notificationsOpen && (
              <NotificationDrawer
                notifications={notifications}
                onRead={async (id) => {
                  await markNotificationRead(id);
                  setNotifications((items) =>
                    items.map((item) =>
                      item.id === id
                        ? { ...item, readAtUtc: new Date().toISOString() }
                        : item,
                    ),
                  );
                }}
                onReadAll={async () => {
                  await markAllNotificationsRead();
                  setNotifications((items) =>
                    items.map((item) => ({
                      ...item,
                      readAtUtc: item.readAtUtc ?? new Date().toISOString(),
                    })),
                  );
                }}
              />
            )}
          </div>
          <button
            className="profile-chip"
            onClick={() => setActiveTab("Profile")}
          >
            <span className="mini-avatar">{initial(user.displayName)}</span>
            <span className="profile-name">{user.displayName}</span>
            <ChevronRight size={15} />
          </button>
        </header>
        <div className="content">
          {error && (
            <div className="inline-error">
              {error}
              <button onClick={() => setError(null)}>Dismiss</button>
            </div>
          )}
          {activeTab === "Home" && (
            <HomeView snapshot={snapshot} onNavigate={setActiveTab} />
          )}
          {activeTab === "Messages" && (
            <MessagesView
              messages={messages}
              user={user}
              linked={isLinked}
              typing={typing}
              onTyping={(isTyping, isPrivate) => {
                void coupleConnectionRef.current?.invoke(
                  "SetTyping",
                  isTyping,
                  isPrivate,
                );
              }}
              onSend={async (body, isPrivate) => {
                try {
                  const sent = await sendMessage(body, isPrivate);
                  setMessages((current) =>
                    current.some((item) => item.id === sent.id)
                      ? current
                      : [...current, sent],
                  );
                } catch (requestError) {
                  setError(errorMessage(requestError));
                }
              }}
            />
          )}
          {activeTab === "Cycle" && (
            <CycleView
              cycleOwner={user.cycleOwner}
              gender={user.gender}
              profile={profile}
              prediction={prediction}
              logs={logs}
              shared={sharedCycle}
              onClaim={async () => {
                try {
                  setProfile(await claimCycle());
                  setUser((current) =>
                    current ? { ...current, cycleOwner: true } : current,
                  );
                } catch (requestError) {
                  setError(errorMessage(requestError));
                }
              }}
              onSaveProfile={async (values) => {
                try {
                  setProfile(await saveCycleProfile(values));
                  setPrediction(await getCyclePrediction());
                  setSharedCycle(await getSharedCycle().catch(() => null));
                } catch (requestError) {
                  setError(errorMessage(requestError));
                }
              }}
              onSaveLog={async (date, values) => {
                try {
                  const saved = await saveCycleLog(date, values);
                  setLogs((current) => [
                    saved,
                    ...current.filter((item) => item.logDate !== date),
                  ]);
                } catch (requestError) {
                  setError(errorMessage(requestError));
                }
              }}
            />
          )}
          {activeTab === "Games" && (
            <GamesView
              user={user}
              linked={isLinked}
              games={games}
              selected={selectedGame}
              countdownSeconds={
                countdown?.sessionId === selectedGame?.id ? countdownSeconds : 0
              }
              onSelect={setSelectedGameId}
              onCreate={async (type, letter) => {
                try {
                  const created = await createGameSession(type, letter);
                  setGames((current) => [created, ...current]);
                  setSelectedGameId(created.id);
                } catch (requestError) {
                  setError(errorMessage(requestError));
                }
              }}
              onEnd={async (id) => {
                try {
                  replaceGame(setGames, await endGameSession(id));
                } catch (requestError) {
                  setError(errorMessage(requestError));
                }
              }}
              onAction={async (session, action, value, index) => {
                try {
                  replaceGame(
                    setGames,
                    await applyGameAction(
                      session.id,
                      session.stateVersion,
                      action,
                      value,
                      index,
                    ),
                  );
                } catch (requestError) {
                  // A partner or the timeout worker may have advanced the
                  // session between render and submit. Refresh immediately so
                  // the board is usable again instead of leaving a stale
                  // version that makes every later action fail.
                  let retried = false;
                  const isVersionConflict =
                    typeof requestError === "object" &&
                    requestError !== null &&
                    "code" in requestError &&
                    (requestError as { code?: string }).code ===
                      "game.version_conflict";
                  try {
                    const fresh = await getGameSession(session.id);
                    replaceGame(setGames, fresh);
                    setSelectedGameId(fresh.id);

                    // On Game is simultaneous: if the partner won the race,
                    // retry this player's submission against the fresh version
                    // while the same round and letter are still active.
                    const previousState = parseState(session.stateJson);
                    const freshState = parseState(fresh.stateJson);
                    const sameRound =
                      session.gameType === "onGame" &&
                      action === "submit" &&
                      Number(previousState.round ?? 0) ===
                        Number(freshState.round ?? 0) &&
                      String(previousState.letter ?? "") ===
                        String(freshState.letter ?? "");
                    const submitted = Array.isArray(freshState.submitted)
                      ? freshState.submitted.map(String)
                      : [];
                    if (
                      isVersionConflict &&
                      sameRound &&
                      !submitted.some(
                        (player) =>
                          player.toLowerCase() === user.id.toLowerCase(),
                      )
                    ) {
                      try {
                        replaceGame(
                          setGames,
                          await applyGameAction(
                            fresh.id,
                            fresh.stateVersion,
                            action,
                            value,
                            index,
                          ),
                        );
                        retried = true;
                      } catch {
                        // Keep the original conflict message if the round
                        // changed again before the retry completed.
                      }
                    }
                  } catch {
                    // Preserve the original, more useful error below.
                  }
                  if (!retried) setError(errorMessage(requestError));
                }
              }}
            />
          )}
          {activeTab === "Map" && (
            <MapView
              linked={isLinked}
              locations={locations}
              partnerColor={user.mapColor}
              onShare={() => void shareLocation()}
              onStop={() => void stopLocation()}
            />
          )}
          {activeTab === "Profile" && (
            <ProfileView
              user={user}
              couple={couple}
              invite={invite}
              onSaveProfile={async (values) => {
                try {
                  const response = await updateProfile(values);
                  setUser(response.user);
                  setCelebration("Profile updated");
                } catch (requestError) {
                  setError(errorMessage(requestError));
                }
              }}
              onInvite={async () => {
                try {
                  const created = await createPartnerInvite();
                  if (created.accessToken) {
                    // Legacy accounts may have a stale couple claim; the invite
                    // endpoint returns a refreshed token after repairing it.
                    window.localStorage.setItem("twogether.accessToken", created.accessToken);
                    const refreshed = await getMe();
                    setUser(refreshed);
                    setCouple(await getCouple().catch(() => null));
                  }
                  setInvite(created);
                } catch (requestError) {
                  setError(errorMessage(requestError));
                }
              }}
              onAccept={async (code) => {
                try {
                  const response = await acceptPartnerInvite(code);
                  setUser(response.user);
                  await loadData(response.user.id);
                  playSound("connect");
                  setCelebration("Your private space is connected");
                } catch (requestError) {
                  setError(errorMessage(requestError));
                }
              }}
              onBreakUp={async () => {
                try {
                  await breakUp();
                  signOut();
                } catch (requestError) {
                  setError(errorMessage(requestError));
                }
              }}
              onLogout={signOut}
              onSettings={() => setActiveTab("Settings")}
            />
          )}
          {activeTab === "Settings" && (
            <SettingsView
              user={user}
              onSave={async (mapColor) => {
                try {
                  const response = await updateProfile({
                    displayName: user.displayName,
                    avatarUrl: user.avatarUrl,
                    mapColor,
                  });
                  setUser(response.user);
                  setCelebration("Map color updated");
                } catch (requestError) {
                  setError(errorMessage(requestError));
                }
              }}
            />
          )}
        </div>
      </section>
      <nav className="mobile-nav">
        {navigation.map(({ label, icon: Icon }) => (
          <button
            className={activeTab === label ? "active" : ""}
            key={label}
            onClick={() => setActiveTab(label)}
          >
            <Icon size={19} />
            <span>{label}</span>
          </button>
        ))}
        <button
          className={activeTab === "Profile" ? "active" : ""}
          onClick={() => setActiveTab("Profile")}
        >
          <UserRound size={19} />
          <span>Profile</span>
        </button>
      </nav>
    </main>
  );
}

function AuthScreen({
  error,
  onSubmit,
}: {
  error: string | null;
  onSubmit: (
    mode: "login" | "register",
    values: {
      email: string;
      password: string;
      displayName: string;
      dateOfBirth: string;
      gender: Gender;
    },
  ) => Promise<void>;
}) {
  const [mode, setMode] = useState<"login" | "register">("login");
  const [busy, setBusy] = useState(false);
  const [values, setValues] = useState({
    email: "",
    password: "",
    displayName: "",
    dateOfBirth: "1998-01-01",
    gender: "preferNotToSay" as Gender,
  });
  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    await onSubmit(mode, values);
    setBusy(false);
  }
  return (
    <main className="auth-screen">
      <section className="auth-panel">
        <Brand />
        <div className="auth-tabs">
          <button
            className={mode === "login" ? "active" : ""}
            onClick={() => setMode("login")}
          >
            Sign in
          </button>
          <button
            className={mode === "register" ? "active" : ""}
            onClick={() => setMode("register")}
          >
            Create account
          </button>
        </div>
        <form onSubmit={submit}>
          {mode === "register" && (
            <>
              <label>
                Display name
                <input
                  required
                  value={values.displayName}
                  onChange={(event) =>
                    setValues({ ...values, displayName: event.target.value })
                  }
                />
              </label>
              <label>
                Date of birth
                <input
                  type="date"
                  required
                  value={values.dateOfBirth}
                  onChange={(event) =>
                    setValues({ ...values, dateOfBirth: event.target.value })
                  }
                />
              </label>
              <label>
                Gender
                <select
                  required
                  value={values.gender}
                  onChange={(event) =>
                    setValues({ ...values, gender: event.target.value as Gender })
                  }
                >
                  <option value="female">Female</option>
                  <option value="male">Male</option>
                  <option value="nonBinary">Non-binary</option>
                  <option value="preferNotToSay">Prefer not to say</option>
                </select>
                <small className="field-help">Used only to personalize cycle support. You can choose not to say.</small>
              </label>
            </>
          )}
          <label>
            Email
            <input
              type="email"
              required
              autoComplete="email"
              value={values.email}
              onChange={(event) =>
                setValues({ ...values, email: event.target.value })
              }
            />
          </label>
          <label>
            Password
            <input
              type="password"
              minLength={8}
              required
              autoComplete={
                mode === "login" ? "current-password" : "new-password"
              }
              value={values.password}
              onChange={(event) =>
                setValues({ ...values, password: event.target.value })
              }
            />
          </label>
          {error && <p className="form-error">{error}</p>}
          <button className="primary-button" disabled={busy}>
            {busy
              ? "Please wait..."
              : mode === "login"
                ? "Sign in"
                : "Create your space"}
          </button>
        </form>
      </section>
    </main>
  );
}
function HomeView({
  snapshot,
  onNavigate,
}: {
  snapshot: DashboardSnapshot;
  onNavigate: (tab: string) => void;
}) {
  const dateLabel = new Intl.DateTimeFormat(undefined, {
    weekday: "long",
    month: "long",
    day: "numeric",
  }).format(new Date(snapshot.generatedAtUtc));
  return (
    <>
      <section className="welcome-row">
        <div>
          <p className="eyebrow">{dateLabel}</p>
          <h1>
            Good evening, {snapshot.currentUserName} <span>&#9825;</span>
          </h1>
          <p className="muted">A little time together goes a long way.</p>
        </div>
      </section>
      <section className="hero-card">
        <div className="hero-copy">
          <div className="hero-kicker">
            <Heart size={14} fill="currentColor" /> Your space, together
          </div>
          <h2>
            Made for your
            <br />
            <em>little moments.</em>
          </h2>
          <p>{snapshot.heroDescription}</p>
          <button
            className="primary-button"
            onClick={() => onNavigate("Messages")}
          >
            Send a love note <Heart size={16} fill="currentColor" />
          </button>
        </div>
        <div className="hero-art" aria-hidden="true">
          <Heart size={108} strokeWidth={1.1} />
        </div>
      </section>
      <section className="section-block">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Stay connected</p>
            <h3>Quick access</h3>
          </div>
        </div>
        <div className="quick-grid">
          <button
            className="quick-card rose"
            onClick={() => onNavigate("Messages")}
          >
            <span className="quick-icon">
              <MessageCircle size={19} />
            </span>
            <strong>Love notes</strong>
            <small>Send something sweet</small>
          </button>
          <button
            className="quick-card violet"
            onClick={() => onNavigate("Cycle")}
          >
            <span className="quick-icon">
              <CalendarDays size={19} />
            </span>
            <strong>Cycle tracker</strong>
            <small>{snapshot.cycleSummary}</small>
          </button>
          <button className="quick-card cyan" onClick={() => onNavigate("Map")}>
            <span className="quick-icon">
              <MapPinned size={19} />
            </span>
            <strong>Live map</strong>
            <small>Share a moment nearby</small>
          </button>
        </div>
      </section>
      <section className="section-block two-column">
        <div className="streak-card">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Our little ritual</p>
              <h3>Connection streak</h3>
            </div>
            <Sparkles size={20} className="pink-icon" />
          </div>
          <div className="streak-number">
            {snapshot.connectionStreakDays} <span>days</span>
          </div>
          <p className="muted">
            Counted from days you share a message or a game.
          </p>
        </div>
        <div className="note-card">
          <div className="note-header">
            <span>From {snapshot.latestNoteAuthor}</span>
          </div>
          <p>&ldquo;{snapshot.latestNote}&rdquo;</p>
          <button
            className="reply-button"
            onClick={() => onNavigate("Messages")}
          >
            <MessageCircle size={15} /> Reply
          </button>
        </div>
      </section>
    </>
  );
}
function MessagesView({
  messages,
  user,
  linked,
  typing,
  onSend,
  onTyping,
}: {
  messages: Message[];
  user: AuthUser;
  linked: boolean;
  typing: TypingState | null;
  onSend: (body: string, isPrivate: boolean) => Promise<void>;
  onTyping: (isTyping: boolean, isPrivate: boolean) => void;
}) {
  const [body, setBody] = useState("");
  const [isPrivate, setPrivate] = useState(false);
  const timer = useRef<number | null>(null);
  function change(value: string) {
    setBody(value);
    onTyping(Boolean(value), isPrivate);
    if (timer.current) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => onTyping(false, isPrivate), 1300);
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    const value = body.trim();
    if (!value) return;
    setBody("");
    onTyping(false, isPrivate);
    await onSend(value, isPrivate);
  }
  return (
    <Feature
      title="Messages"
      subtitle="Live conversation for just the two of you."
    >
      {!linked && (
        <EmptyState text="Link your partner from Profile before sending messages." />
      )}
      <FirstUse
        id="chat"
        text="Your messages appear live. Switch on private mode when you want a more discreet conversation."
      />
      <div className="messages-panel">
        <div className="message-list">
          {messages.length === 0 ? (
            <EmptyState text="No messages yet." />
          ) : (
            messages.map((message) => (
              <div
                className={`message-bubble ${message.senderUserId === user.id ? "mine" : ""} ${message.isPrivate ? "private" : ""}`}
                key={message.id}
              >
                {message.isPrivate && <LockKeyhole size={12} />}
                <p>{message.body}</p>
                <small>
                  {new Date(message.createdAtUtc).toLocaleString([], {
                    dateStyle: "short",
                    timeStyle: "short",
                  })}
                  {message.editedAtUtc ? " (edited)" : ""}
                </small>
              </div>
            ))
          )}
          {typing && (
            <div className="typing-indicator">
              {typing.displayName} is typing
              <span />
              <span />
              <span />
            </div>
          )}
        </div>
        <form className="message-composer" onSubmit={submit}>
          <div className="composer-input">
            <input
              disabled={!linked}
              maxLength={4000}
              placeholder={
                linked
                  ? isPrivate
                    ? "Write a private message"
                    : "Write a message"
                  : "Link your partner first"
              }
              value={body}
              onChange={(event) => change(event.target.value)}
            />
            <label className="private-toggle">
              <input
                type="checkbox"
                checked={isPrivate}
                onChange={(event) => {
                  setPrivate(event.target.checked);
                  onTyping(Boolean(body), event.target.checked);
                }}
              />
              <LockKeyhole size={13} />
              <span>Private</span>
            </label>
          </div>
          <button disabled={!linked || !body.trim()} aria-label="Send message">
            <Send size={18} />
          </button>
        </form>
      </div>
    </Feature>
  );
}
function CycleView({
  cycleOwner,
  gender,
  profile,
  prediction,
  logs,
  shared,
  onClaim,
  onSaveProfile,
  onSaveLog,
}: {
  cycleOwner: boolean;
  gender?: Gender;
  profile: CycleProfile | null;
  prediction: CyclePrediction | null;
  logs: CycleLog[];
  shared: SharedCycle | null;
  onClaim: () => Promise<void>;
  onSaveProfile: (values: {
    averageCycleLengthDays: number;
    averagePeriodLengthDays: number;
    lastPeriodStartDate: string | null;
    shareLevel: string;
  }) => Promise<void>;
  onSaveLog: (date: string, values: Omit<CycleLog, "logDate">) => Promise<void>;
}) {
  const [settings, setSettings] = useState({
    averageCycleLengthDays: profile?.averageCycleLengthDays ?? 28,
    averagePeriodLengthDays: profile?.averagePeriodLengthDays ?? 5,
    lastPeriodStartDate: profile?.lastPeriodStartDate ?? "",
    shareLevel: profile?.shareLevel ?? "predictionsOnly",
  });
  const today = new Date().toISOString().slice(0, 10);
  const [daily, setDaily] = useState({
    flowLevel: "none",
    symptoms: [] as string[],
    mood: "calm",
    hadSex: null as boolean | null,
    protectionUsed: null as boolean | null,
    notes: "" as string | null,
  });
  useEffect(() => {
    if (profile)
      setSettings({
        averageCycleLengthDays: profile.averageCycleLengthDays,
        averagePeriodLengthDays: profile.averagePeriodLengthDays,
        lastPeriodStartDate: profile.lastPeriodStartDate ?? "",
        shareLevel: profile.shareLevel,
      });
  }, [profile]);
  const activeProfile = profile?.lastPeriodStartDate
    ? profile
    : (shared?.profile ?? profile ?? null);
  const activePrediction = profile?.lastPeriodStartDate
    ? prediction
    : (shared?.prediction ?? prediction ?? null);
  const activeLogs = profile?.lastPeriodStartDate
    ? logs
    : shared?.logs?.length
      ? shared.logs
      : logs;
  return (
    <Feature
      title="Cycle tracker"
      subtitle="Calendar, consent-based sharing, and supportive daily care."
    >
      <FirstUse
        id="cycle"
        text="Cycle tracking is personal. Partner sharing stays off until you choose a level, and predictions are not medical advice."
      />
      {cycleOwner && gender === "female" ? (
        <div className="feature-grid">
          <form
            className="tool-panel"
            onSubmit={(event) => {
              event.preventDefault();
              void onSaveProfile({
                ...settings,
                lastPeriodStartDate: settings.lastPeriodStartDate || null,
              });
            }}
          >
            <h3>Cycle settings</h3>
            <label>
              Last period started
              <input
                type="date"
                value={settings.lastPeriodStartDate}
                onChange={(event) =>
                  setSettings({
                    ...settings,
                    lastPeriodStartDate: event.target.value,
                  })
                }
              />
            </label>
            <div className="field-row">
              <label>
                Cycle length
                <input
                  type="number"
                  min={15}
                  max={60}
                  value={settings.averageCycleLengthDays}
                  onChange={(event) =>
                    setSettings({
                      ...settings,
                      averageCycleLengthDays: Number(event.target.value),
                    })
                  }
                />
              </label>
              <label>
                Period length
                <input
                  type="number"
                  min={1}
                  max={14}
                  value={settings.averagePeriodLengthDays}
                  onChange={(event) =>
                    setSettings({
                      ...settings,
                      averagePeriodLengthDays: Number(event.target.value),
                    })
                  }
                />
              </label>
            </div>
            <label>
              Partner sharing
              <select
                value={settings.shareLevel}
                onChange={(event) =>
                  setSettings({ ...settings, shareLevel: event.target.value })
                }
              >
                <option value="none">Private</option>
                <option value="predictionsOnly">Predictions only</option>
                <option value="fullDetail">Full detail</option>
              </select>
            </label>
            <button className="primary-button">Save settings</button>
          </form>
          <div className="tool-panel prediction-panel">
            <h3>Prediction</h3>
            <Metric
              label="Next period"
              value={formatDate(activePrediction?.nextPeriodStart)}
            />
            <Metric
              label="Fertile window"
              value={formatDate(activePrediction?.fertileWindowStart)}
            />
            <Metric
              label="Expected period end"
              value={formatDate(activePrediction?.periodEnd)}
            />
          </div>
          <form
            className="tool-panel"
            onSubmit={(event) => {
              event.preventDefault();
              void onSaveLog(today, daily);
            }}
          >
            <h3>Today</h3>
            <div className="field-row">
              <label>
                Flow
                <select
                  value={daily.flowLevel}
                  onChange={(event) =>
                    setDaily({ ...daily, flowLevel: event.target.value })
                  }
                >
                  <option value="none">None</option>
                  <option value="spotting">Spotting</option>
                  <option value="light">Light</option>
                  <option value="medium">Medium</option>
                  <option value="heavy">Heavy</option>
                </select>
              </label>
              <label>
                Mood
                <select
                  value={daily.mood ?? "calm"}
                  onChange={(event) =>
                    setDaily({ ...daily, mood: event.target.value })
                  }
                >
                  <option value="happy">Happy</option>
                  <option value="calm">Calm</option>
                  <option value="sensitive">Sensitive</option>
                  <option value="anxious">Anxious</option>
                  <option value="irritable">Irritable</option>
                  <option value="sad">Sad</option>
                </select>
              </label>
            </div>
            <div className="consent-row">
              <span>Had sex today?</span>
              <button
                type="button"
                className={daily.hadSex === true ? "active" : ""}
                onClick={() => setDaily({ ...daily, hadSex: true })}
              >
                Yes
              </button>
              <button
                type="button"
                className={daily.hadSex === false ? "active" : ""}
                onClick={() => setDaily({ ...daily, hadSex: false })}
              >
                No
              </button>
            </div>
            {daily.hadSex && (
              <div className="consent-row">
                <span>Protection used?</span>
                <button
                  type="button"
                  className={daily.protectionUsed === true ? "active" : ""}
                  onClick={() => setDaily({ ...daily, protectionUsed: true })}
                >
                  Yes
                </button>
                <button
                  type="button"
                  className={daily.protectionUsed === false ? "active" : ""}
                  onClick={() => setDaily({ ...daily, protectionUsed: false })}
                >
                  No
                </button>
              </div>
            )}
            <label>
              Notes
              <textarea
                value={daily.notes ?? ""}
                onChange={(event) =>
                  setDaily({ ...daily, notes: event.target.value })
                }
              />
            </label>
            <button className="primary-button">Save today</button>
          </form>
          {gender === "female" && (
            <ExercisePanel phase={cyclePhase(today, activeProfile)} />
          )}
        </div>
      ) : shared?.profile ? (
        <div className="tool-panel shared-cycle-note">
          <h3>Partner cycle calendar</h3>
          <p className="muted">
            Your partner has chosen to share cycle predictions with you. Their
            calendar and expected period dates are shown below.
          </p>
        </div>
      ) : gender === "female" ? (
        <div className="tool-panel shared-cycle-note">
          <h3>Cycle calendar owner</h3>
          <p className="muted">
            Only the partner who tracks the menstrual cycle can configure dates
            and daily logs. Claim this role only if this is your cycle.
          </p>
          <button className="secondary-button" onClick={() => void onClaim()}>
            I track this cycle
          </button>
        </div>
      ) : (
        <div className="tool-panel shared-cycle-note">
          <h3>Cycle support</h3>
          <p className="muted">Menstrual tracking and movement guidance are available only to the partner who identifies as female.</p>
        </div>
      )}
      <section className="section-block">
        <div className="section-heading">
          <div>
            <p className="eyebrow">This month</p>
            <h3>Cycle calendar</h3>
          </div>
        </div>
        <CycleCalendar
          profile={activeProfile}
          prediction={activePrediction}
          logs={activeLogs}
        />
      </section>
    </Feature>
  );
}
function GamesView({
  user,
  linked,
  games,
  selected,
  countdownSeconds,
  onSelect,
  onCreate,
  onEnd,
  onAction,
}: {
  user: AuthUser;
  linked: boolean;
  games: GameSession[];
  selected: GameSession | null;
  countdownSeconds: number;
  onSelect: (id: string) => void;
  onCreate: (type: string, letter?: string) => Promise<void>;
  onEnd: (id: string) => Promise<void>;
  onAction: (
    session: GameSession,
    action: string,
    value?: string,
    index?: number,
  ) => Promise<void>;
}) {
  const [answer, setAnswer] = useState("");
  const [actionPending, setActionPending] = useState(false);
  const [onGameAnswers, setOnGameAnswers] = useState({
    Name: "",
    Place: "",
    Thing: "",
    Food: "",
  });
  const state = useMemo(
    () => parseState(selected?.stateJson),
    [selected?.stateJson],
  );
  const cards = (state.cards ?? state.Cards ?? []) as {
    index: number;
    value: number | null;
    matched: boolean;
  }[];
  const categories = Array.isArray(state.categories)
    ? (state.categories as unknown[]).map(String)
    : ["Name", "Place", "Thing", "Food"];
  const submitted = Array.isArray(state.submitted)
    ? state.submitted.map(String)
    : [];
  const hasSubmitted = submitted.some(
    (player) => player.toLowerCase() === user.id.toLowerCase(),
  );
  const scores = (state.scores ?? state.Scores ?? {}) as Record<string, number>;
  const myScore = Number(scores[user.id] ?? scores[user.id.toLowerCase()] ?? 0);
  const partnerScore = Object.entries(scores).find(
    ([player]) => player.toLowerCase() !== user.id.toLowerCase(),
  )?.[1] ?? 0;
  const [roundNow, setRoundNow] = useState(() => Date.now());
  const roundSeconds = selected?.deadlineUtc
    ? Math.max(0, Math.ceil((new Date(selected.deadlineUtc).getTime() - roundNow) / 1000))
    : 0;
  useEffect(() => {
    if (selected?.gameType === "onGame")
      setOnGameAnswers({ Name: "", Place: "", Thing: "", Food: "" });
  }, [selected?.id, state.round, state.letter]);
  useEffect(() => {
    if (selected?.gameType !== "onGame" || selected.status !== "inProgress") return;
    const timer = window.setInterval(() => setRoundNow(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [selected?.gameType, selected?.status, selected?.deadlineUtc]);
  const prompt = String(state.Prompt ?? state.prompt ?? "Take your turn");
  const canPlay =
    countdownSeconds === 0 &&
    (!selected?.countdownEndsAtUtc ||
      new Date(selected.countdownEndsAtUtc).getTime() <= Date.now());
  const submitAction = (
    session: GameSession,
    action: string,
    value?: string,
    index?: number,
  ) => {
    if (actionPending) return;
    setActionPending(true);
    void onAction(session, action, value, index).finally(() => setActionPending(false));
  };
  return (
    <Feature
      title="Games"
      subtitle="Invite, accept, countdown, then play together."
    >
      <FirstUse
        id="games"
        text="When a game is invited, the other partner can accept from anywhere in the app. You both enter after the five-second countdown."
      />
      {!linked && (
        <EmptyState text="Link your partner from Profile before starting a game." />
      )}
      <div className="game-picker">
        {gameTypes.map((game) => {
          const Icon = game.icon;
          return (
            <button
              disabled={
                !linked ||
                games.some(
                  (item) =>
                    item.status === "waitingForPartner" ||
                    item.status === "inProgress",
                )
              }
              key={game.value}
              onClick={() =>
                void onCreate(
                  game.value,
                  undefined,
                )
              }
            >
              <Icon size={18} />
              <strong>{game.title}</strong>
              <small>{game.description}</small>
            </button>
          );
        })}
      </div>
      <p className="game-rule-note">On Game chooses a fresh letter at random when both of you join.</p>
      {games.length > 0 && (
        <div className="session-tabs">
          {games.slice(0, 8).map((game) => (
            <button
              className={selected?.id === game.id ? "active" : ""}
              key={game.id}
              onClick={() => onSelect(game.id)}
            >
              {humanize(game.gameType)} <small>{humanize(game.status)}</small>
            </button>
          ))}
        </div>
      )}
      {selected && (
        <div className="tool-panel game-board">
          <div className="game-status">
            <div>
              <p className="eyebrow">{humanize(selected.status)}</p>
              <h3>{humanize(selected.gameType)}</h3>
            </div>
            {["waitingForPartner", "inProgress"].includes(selected.status) && (
              <button
                className="secondary-button"
                onClick={() => void onEnd(selected.id)}
              >
                End game
              </button>
            )}
          </div>
          {countdownSeconds > 0 && (
            <div className="game-countdown">
              <span>{countdownSeconds}</span>
              <p>Get ready together</p>
            </div>
          )}
          {selected.status === "waitingForPartner" && (
            <EmptyState text="Your invitation is waiting for your partner." />
          )}
          {selected.status === "inProgress" && canPlay && (
            <>
              {selected.gameType === "memoryMatch" ? (
                <div className="memory-grid">
                  {cards.map((card) => (
                    <button
                      disabled={
                        selected.currentTurnUserId !== user.id ||
                        card.matched ||
                        card.value !== null
                      }
                      className={card.matched ? "matched" : ""}
                      key={card.index}
                      onClick={() =>
                        submitAction(selected, "flip", undefined, card.index)
                      }
                    >
                      {card.value ?? "?"}
                    </button>
                  ))}
                </div>
              ) : selected.gameType === "onGame" ? (
                <form
                  className="on-game-board"
                  onSubmit={(event) => {
                    event.preventDefault();
                    submitAction(
                      selected,
                      "submit",
                      JSON.stringify(onGameAnswers),
                    );
                  }}
                >
                  <div className="on-game-letter">
                    {String(state.letter ?? "A")}
                  </div>
                  <p className="muted">
                    Round {Number(state.round ?? 0) + 1} of 3. Submit all four
                    answers before the 90-second round ends.
                  </p>
                  <div className="on-game-scoreboard">
                    <span>Your score <strong>{myScore}</strong></span>
                    <span className={roundSeconds <= 10 ? "round-timer urgent" : "round-timer"}>{roundSeconds}s</span>
                    <span>Partner <strong>{partnerScore}</strong></span>
                  </div>
                  <div className="on-game-fields">
                    {categories.map((category) => (
                      <label key={category}>
                        {category}
                        <input
                          required
                          disabled={hasSubmitted}
                          value={
                            onGameAnswers[
                              category as keyof typeof onGameAnswers
                            ]
                          }
                          onChange={(event) =>
                            setOnGameAnswers({
                              ...onGameAnswers,
                              [category]: event.target.value,
                            })
                          }
                          placeholder={`${category} starting with ${String(state.letter ?? "A")}`}
                        />
                      </label>
                    ))}
                  </div>
                  <button className="primary-button" disabled={hasSubmitted || actionPending}>
                    <Trophy size={16} /> {hasSubmitted ? "Waiting for your partner" : "Submit round"}
                  </button>
                  {hasSubmitted && <p className="muted">Your answers are hidden until both of you submit.</p>}
                  {Boolean(state.winnerUserId) && (
                    <p className="status-ok">
                      Winner announced. Scores update for the next round.
                    </p>
                  )}
                </form>
              ) : (
                <form
                  className="answer-form"
                  onSubmit={(event) => {
                    event.preventDefault();
                    if (!answer.trim()) return;
                    submitAction(selected, "answer", answer.trim());
                    setAnswer("");
                  }}
                >
                  <h4>{prompt}</h4>
                  <p className="muted">
                    {selected.currentTurnUserId === user.id
                      ? "Your turn"
                      : "Waiting for your partner"}
                  </p>
                  <div>
                    <input
                      disabled={selected.currentTurnUserId !== user.id || actionPending}
                      value={answer}
                      onChange={(event) => setAnswer(event.target.value)}
                      placeholder="Your answer"
                    />
                    <button
                      className="primary-button"
                      disabled={selected.currentTurnUserId !== user.id || actionPending}
                    >
                      Submit
                    </button>
                  </div>
                </form>
              )}
            </>
          )}
          {selected.status === "completed" && (
            <EmptyState text="Game complete. Start another when you are ready." />
          )}
          {selected.status === "abandoned" && (
            <EmptyState text="This game was ended." />
          )}
        </div>
      )}
    </Feature>
  );
}
function MapView({
  linked,
  locations,
  partnerColor,
  onShare,
  onStop,
}: {
  linked: boolean;
  locations: CoupleLocation[];
  partnerColor: string;
  onShare: () => void;
  onStop: () => void;
}) {
  const together =
    locations.length >= 2 &&
    distanceMeters(locations[0], locations[1]) <
      Math.max(100, locations[0].accuracyMeters + locations[1].accuracyMeters);
  return (
    <Feature
      title="Live map"
      subtitle="Share your location only when you choose to."
    >
      <FirstUse
        id="map"
        text="Location is off by default. Sharing sends your approximate live position to your linked partner; stop sharing any time."
      />
      {!linked ? (
        <EmptyState text="Link your partner before sharing location." />
      ) : (
        <>
          <div className="map-actions">
            <button className="primary-button" onClick={onShare}>
              <MapPinned size={16} /> Share my location
            </button>
            <button className="secondary-button" onClick={onStop}>
              Stop sharing
            </button>
            {together && (
              <div className="together-banner">
                <Heart fill="currentColor" size={18} /> You are together
              </div>
            )}
          </div>
          <LiveMap locations={locations} partnerColor={partnerColor} />
          <div className="map-legend">
            {locations.map((location) => (
              <span key={location.userId}>
                <i className={location.isCurrentUser ? "mine" : "partner"} />
                {location.displayName}
              </span>
            ))}
          </div>
        </>
      )}
    </Feature>
  );
}
function ProfileView({
  user,
  couple,
  invite,
  onInvite,
  onAccept,
  onSaveProfile,
  onBreakUp,
  onLogout,
  onSettings,
}: {
  user: AuthUser;
  couple: Couple | null;
  invite: PartnerInvite | null;
  onInvite: () => Promise<void>;
  onAccept: (code: string) => Promise<void>;
  onSaveProfile: (values: {
    displayName: string;
    avatarUrl: string | null;
    mapColor: string;
  }) => Promise<void>;
  onBreakUp: () => Promise<void>;
  onLogout: () => void;
  onSettings: () => void;
}) {
  const [code, setCode] = useState("");
  const [confirming, setConfirming] = useState(false);
  const [displayName, setDisplayName] = useState(user.displayName);
  const [avatarUrl, setAvatarUrl] = useState(user.avatarUrl ?? "");
  const [mapColor, setMapColor] = useState(user.mapColor || "#f45c91");
  const [saving, setSaving] = useState(false);
  return (
    <Feature
      title="Profile"
      subtitle="Manage your account and private couple connection."
    >
      <div className="feature-grid">
        <div className="tool-panel">
          <div className="avatar-preview">
            {avatarUrl ? <img src={avatarUrl} alt="" /> : initial(displayName)}
          </div>
          <h3>{user.displayName}</h3>
          <p className="muted">{user.email}</p>
          <form
            className="profile-editor"
            onSubmit={async (event) => {
              event.preventDefault();
              setSaving(true);
              try {
                await onSaveProfile({
                  displayName,
                  avatarUrl: avatarUrl || null,
                  mapColor,
                });
              } finally {
                setSaving(false);
              }
            }}
          >
            <label>
              Nickname
              <input
                value={displayName}
                maxLength={100}
                onChange={(event) => setDisplayName(event.target.value)}
              />
            </label>
            <label>
              Avatar URL
              <input
                value={avatarUrl}
                placeholder="https://..."
                onChange={(event) => setAvatarUrl(event.target.value)}
              />
            </label>
            <label className="color-setting">
              Map marker color
              <input
                type="color"
                value={mapColor}
                onChange={(event) => setMapColor(event.target.value)}
              />
            </label>
            <button className="secondary-button" disabled={saving}>
              <UserPen size={15} /> {saving ? "Saving..." : "Save profile"}
            </button>
          </form>
          <button className="secondary-button" onClick={onLogout}>
            <LogOut size={16} /> Sign out
          </button>
          <button className="text-button" onClick={onSettings}>
            <Palette size={15} /> Map settings
          </button>
        </div>
        <div className="tool-panel">
          <h3>Partner connection</h3>
          {couple?.status === "active" ? (
            <>
              <p className="status-ok">Connected</p>
              <p className="muted">
                {couple.members
                  .map((member) => member.displayName)
                  .join(" and ")}
              </p>
              {confirming ? (
                <div className="breakup-confirm">
                  <p>
                    Before you end this space, take a moment. Your shared notes,
                    games, and live connection will no longer be available
                    together.
                  </p>
                  <div>
                    <button
                      className="secondary-button"
                      onClick={() => setConfirming(false)}
                    >
                      Keep us connected
                    </button>
                    <button
                      className="danger-button"
                      onClick={() => void onBreakUp()}
                    >
                      End connection
                    </button>
                  </div>
                </div>
              ) : (
                <button
                  className="text-button danger-text"
                  onClick={() => setConfirming(true)}
                >
                  End our connection
                </button>
              )}
            </>
          ) : (
            <>
              <p className="muted">
                Create a six-digit invite code for your partner, or enter the
                code they shared with you.
              </p>
              <button
                className="primary-button"
                onClick={() => void onInvite()}
              >
                Create invite code
              </button>
              {invite && (
                <div className="invite-code">
                  <strong>{invite.code}</strong>
                  <small>
                    Expires {new Date(invite.expiresAtUtc).toLocaleDateString()}
                  </small>
                </div>
              )}
              <form
                className="accept-form"
                onSubmit={(event) => {
                  event.preventDefault();
                  if (code.trim()) void onAccept(code.trim());
                }}
              >
                <input
                  inputMode="numeric"
                  maxLength={6}
                  placeholder="Enter 6-digit code"
                  value={code}
                  onChange={(event) =>
                    setCode(event.target.value.replace(/\D/g, ""))
                  }
                />
                <button className="secondary-button">Connect</button>
              </form>
            </>
          )}
        </div>
      </div>
    </Feature>
  );
}
function SettingsView({
  user,
  onSave,
}: {
  user: AuthUser;
  onSave: (mapColor: string) => Promise<void>;
}) {
  const [mapColor, setMapColor] = useState(user.mapColor || "#f45c91");
  return (
    <Feature
      title="Settings"
      subtitle="Choose how your partner appears on the live map."
    >
      <div className="tool-panel">
        <h3>Map marker</h3>
        <label className="color-setting">
          Partner marker color
          <input
            type="color"
            value={mapColor}
            onChange={(event) => setMapColor(event.target.value)}
          />
        </label>
        <p className="muted">
          This color is visible to both of you on the map.
        </p>
        <button
          className="primary-button"
          onClick={() => void onSave(mapColor)}
        >
          <Palette size={16} /> Save map color
        </button>
      </div>
    </Feature>
  );
}
function GameInvitationModal({
  invitation,
  onAccept,
  onDismiss,
}: {
  invitation: GameInvitation;
  onAccept: () => void;
  onDismiss: () => void;
}) {
  return (
    <div className="modal-backdrop">
      <section className="game-invitation">
        <button
          className="modal-close"
          onClick={onDismiss}
          aria-label="Dismiss"
        >
          <X size={18} />
        </button>
        <Gamepad2 size={28} />
        <p className="eyebrow">Game invitation</p>
        <h2>
          {invitation.invitedByName} wants to play{" "}
          {humanize(invitation.gameType)}
        </h2>
        <p>
          Accept to bring both of you into the game room after a short
          countdown.
        </p>
        <button className="primary-button" onClick={onAccept}>
          <Play size={16} /> Accept and start
        </button>
      </section>
    </div>
  );
}
function NotificationDrawer({
  notifications,
  onRead,
  onReadAll,
}: {
  notifications: AppNotification[];
  onRead: (id: string) => Promise<void>;
  onReadAll: () => Promise<void>;
}) {
  return (
    <section className="notification-drawer">
      <div>
        <strong>Notifications</strong>
        <button className="text-button" onClick={() => void onReadAll()}>
          Mark all read
        </button>
      </div>
      {notifications.length === 0 ? (
        <p className="muted">Nothing new right now.</p>
      ) : (
        notifications.slice(0, 8).map((item) => (
          <button
            className={item.readAtUtc ? "read" : ""}
            key={item.id}
            onClick={() => void onRead(item.id)}
          >
            <strong>{item.title}</strong>
            <span>{item.body}</span>
            <small>{relativeTime(item.createdAtUtc)}</small>
          </button>
        ))
      )}
    </section>
  );
}
function CycleCalendar({
  profile,
  prediction,
  logs,
}: {
  profile: CycleProfile | null;
  prediction: CyclePrediction | null;
  logs: CycleLog[];
}) {
  const start = new Date();
  start.setDate(1);
  const days = Array.from(
    { length: 35 },
    (_, index) =>
      new Date(
        start.getFullYear(),
        start.getMonth(),
        index - start.getDay() + 1,
      ),
  );
  const logMap = new Map(logs.map((log) => [log.logDate, log]));
  return (
    <>
      <div className="calendar-legend">
        <span>
          <i className="period" /> Period
        </span>
        <span>
          <i className="fertile" /> Fertile
        </span>
        <span>
          <i className="sex" /> Sex logged
        </span>
      </div>
      <div className="cycle-calendar">
        {["S", "M", "T", "W", "T", "F", "S"].map((day, index) => (
          <b key={`${day}-${index}`}>{day}</b>
        ))}
        {days.map((day) => {
          const key = localDate(day);
          const phase = cyclePhase(key, profile, prediction);
          const log = logMap.get(key);
          return (
            <div
              className={`calendar-day ${day.getMonth() !== start.getMonth() ? "outside" : ""} ${phase}`}
              key={key}
            >
              <span>{day.getDate()}</span>
              {log?.hadSex && <small>&#9825;</small>}
            </div>
          );
        })}
      </div>
    </>
  );
}
function ExercisePanel({ phase }: { phase: string }) {
  const [photoLoaded, setPhotoLoaded] = useState(true);
  const exercise =
    phase === "period"
      ? ["Gentle stretch", "Cat-cow", "Restorative walk"]
      : phase === "fertile"
        ? ["Light strength", "Dance", "Mobility flow"]
        : ["Easy yoga", "Brisk walk", "Core breathing"];
  return (
    <div className="tool-panel exercise-panel">
      <img
        className={`exercise-photo ${photoLoaded ? "" : "failed"}`}
        src={
          phase === "period"
            ? "https://images.unsplash.com/photo-1544367567-0f2fcb009e0b?auto=format&fit=crop&w=900&q=80"
            : phase === "fertile"
              ? "https://images.unsplash.com/photo-1518611012118-696072aa579a?auto=format&fit=crop&w=900&q=80"
              : "https://images.unsplash.com/photo-1571019614242-c5c5dee9f50b?auto=format&fit=crop&w=900&q=80"
        }
        alt="Person performing a gentle workout"
        onLoad={() => setPhotoLoaded(true)}
        onError={() => setPhotoLoaded(false)}
      />
      <h3>Today’s gentle movement</h3>
      {!photoLoaded && (
        <div className="exercise-fallback" aria-hidden="true">
          <div className="exercise-orbit" />
          <div className="exercise-figure">
            <span className="exercise-head" />
            <span className="exercise-body" />
            <span className="exercise-arm left" />
            <span className="exercise-arm right" />
            <span className="exercise-leg left" />
            <span className="exercise-leg right" />
          </div>
        </div>
      )}
      <p className="muted">{exercise.join(" · ")}</p>
      <small>
        Adjust for comfort and follow clinical advice for pain, pregnancy,
        injury, or health conditions.
      </small>
    </div>
  );
}
function FirstUse({ id, text }: { id: string; text: string }) {
  const [visible, setVisible] = useState(false);
  useEffect(() => {
    setVisible(
      window.localStorage.getItem(`twogether.onboarding.${id}`) !== "seen",
    );
  }, [id]);
  if (!visible) return null;
  return (
    <div className="first-use">
      <p>{text}</p>
      <button
        onClick={() => {
          window.localStorage.setItem(`twogether.onboarding.${id}`, "seen");
          setVisible(false);
        }}
      >
        Got it
      </button>
    </div>
  );
}
function Feature({
  title,
  subtitle,
  children,
}: {
  title: string;
  subtitle: string;
  children: React.ReactNode;
}) {
  return (
    <>
      <section className="welcome-row">
        <div>
          <p className="eyebrow">Your private space</p>
          <h1>{title}</h1>
          <p className="muted">{subtitle}</p>
        </div>
      </section>
      <section className="section-block">{children}</section>
    </>
  );
}
function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="metric">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}
function EmptyState({ text }: { text: string }) {
  return <div className="empty-state">{text}</div>;
}
function StateScreen({ text }: { text: string }) {
  return (
    <main className="state-screen">
      <div>
        <div className="loading-heart">
          <Heart size={28} fill="currentColor" />
        </div>
        <p>{text}</p>
      </div>
    </main>
  );
}
function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className={compact ? "brand compact" : "brand"}>
      <div className="brand-mark">
        <Heart size={21} fill="currentColor" />
      </div>
      <span>twogether</span>
    </div>
  );
}
function initial(value: string) {
  return value.trim().charAt(0).toUpperCase() || "?";
}
function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : "Something went wrong.";
}
function formatDate(value?: string | null) {
  return value
    ? new Intl.DateTimeFormat(undefined, {
        month: "short",
        day: "numeric",
        year: "numeric",
      }).format(new Date(`${value}T00:00:00`))
    : "Not available";
}
// Fed straight from wire data, so it takes unknown rather than trusting the
// hand-written DTO types: a non-string here used to crash the whole render.
function humanize(value: unknown) {
  return String(value ?? "")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .replace(/^./, (letter) => letter.toUpperCase());
}
function parseState(value?: string) {
  try {
    return value ? (JSON.parse(value) as Record<string, unknown>) : {};
  } catch {
    return {};
  }
}
function replaceGame(
  setGames: React.Dispatch<React.SetStateAction<GameSession[]>>,
  game: GameSession,
) {
  setGames((current) =>
    current.some((item) => item.id === game.id)
      ? current.map((item) => (item.id === game.id ? game : item))
      : [game, ...current],
  );
}
function localDate(value: Date) {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, "0");
  const day = String(value.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}
function cyclePhase(
  date: string,
  profile: CycleProfile | null,
  prediction?: CyclePrediction | null,
) {
  if (!profile?.lastPeriodStartDate) return "other";
  const start = new Date(`${profile.lastPeriodStartDate}T00:00:00`).getTime();
  const current = new Date(`${date}T00:00:00`).getTime();
  const cycleDay =
    ((Math.floor((current - start) / 86400000) %
      profile.averageCycleLengthDays) +
      profile.averageCycleLengthDays) %
    profile.averageCycleLengthDays;
  if (cycleDay < profile.averagePeriodLengthDays) return "period";
  const fertileStart = Math.max(0, profile.averageCycleLengthDays - 19);
  if (cycleDay >= fertileStart && cycleDay <= fertileStart + 6)
    return "fertile";
  return prediction?.nextPeriodStart === date ? "period" : "other";
}
function distanceMeters(first: CoupleLocation, second: CoupleLocation) {
  const radius = 6371000;
  const dLat = ((second.latitude - first.latitude) * Math.PI) / 180;
  const dLng = ((second.longitude - first.longitude) * Math.PI) / 180;
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos((first.latitude * Math.PI) / 180) *
      Math.cos((second.latitude * Math.PI) / 180) *
      Math.sin(dLng / 2) ** 2;
  return radius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}
function relativeTime(value: string) {
  const seconds = Math.max(
    0,
    Math.round((Date.now() - new Date(value).getTime()) / 1000),
  );
  return seconds < 60
    ? "now"
    : seconds < 3600
      ? `${Math.floor(seconds / 60)}m ago`
      : `${Math.floor(seconds / 3600)}h ago`;
}
function notifyBrowser(title: string, body: string) {
  if (typeof window === "undefined" || !("Notification" in window)) return;
  if (
    Notification.permission === "granted" &&
    document.visibilityState !== "visible"
  )
    new Notification(title, { body });
  else if (Notification.permission === "default")
    void Notification.requestPermission();
}
