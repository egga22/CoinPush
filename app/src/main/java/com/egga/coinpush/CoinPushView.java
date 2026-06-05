package com.egga.coinpush;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public final class CoinPushView extends View {
    private static final int PREF_VERSION = 1;
    private static final int TOKEN_COIN = 0;
    private static final int TOKEN_CHIP = 1;
    private static final int TOKEN_CARD = 2;
    private static final int TOKEN_MEDAL = 3;

    private static final int EVENT_DROP = 1;
    private static final int EVENT_FRONT = 2;
    private static final int EVENT_CARD = 3;
    private static final int EVENT_CHIP = 4;
    private static final int EVENT_JACKPOT = 5;
    private static final int EVENT_BUMPER = 6;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF playRect = new RectF();
    private final RectF topRect = new RectF();
    private final RectF controlRect = new RectF();
    private final RectF dropButton = new RectF();
    private final RectF autoButton = new RectF();
    private final RectF pauseButton = new RectF();
    private final RectF aimTrack = new RectF();
    private final RectF tmpRect = new RectF();
    private final Path trayPath = new Path();
    private final Random random = new Random();
    private final ArrayList<Token> tokens = new ArrayList<>();
    private final ArrayList<FloatText> floatTexts = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Objective> objectives = new ArrayList<>();
    private final ArrayList<Peg> pegs = new ArrayList<>();
    private final SharedPreferences prefs;

    private ToneGenerator tone;
    private boolean running;
    private boolean autoDrop;
    private boolean paused;
    private boolean soundEnabled = true;
    private boolean aimDragging;
    private boolean dropHeld;
    private long lastFrameMs;
    private long lastSaveMs;
    private float density = 1f;
    private float textScale = 1f;
    private float pusherTime;
    private float pusherY;
    private float previousPusherY;
    private float pusherVelocity;
    private float aim = 0.5f;
    private float dropCooldown;
    private float refillClock;
    private float bonusGateTime;
    private int coinsBank;
    private int tickets;
    private int level;
    private int xp;
    private int jackpotWins;
    private int cardSets;
    private int lifetimeDrops;
    private int lifetimeFrontFalls;
    private int serviceRefills;
    private float jackpotMeter;
    private final int[] cardCounts = new int[6];

    public CoinPushView(Context context) {
        super(context);
        setFocusable(true);
        setKeepScreenOn(true);
        density = getResources().getDisplayMetrics().density;
        textScale = getResources().getDisplayMetrics().scaledDensity;
        prefs = context.getSharedPreferences("coinpush-save", Context.MODE_PRIVATE);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
        load();
        pusherY = 0.205f;
        previousPusherY = pusherY;
        seedTrayIfNeeded();
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 45);
        } catch (RuntimeException ignored) {
            tone = null;
        }
    }

    public void resume() {
        if (!running) {
            running = true;
            lastFrameMs = SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }
    }

    public void pause() {
        running = false;
        save();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float top = Math.max(dp(48), h * 0.105f);
        float bottom = Math.max(dp(138), h * 0.18f);
        topRect.set(0, 0, w, top);
        controlRect.set(0, h - bottom, w, h);
        float sidePad = Math.max(dp(10), w * 0.026f);
        playRect.set(sidePad, top + dp(5), w - sidePad, controlRect.top - dp(8));

        float buttonH = Math.min(dp(78), controlRect.height() * 0.58f);
        float buttonW = Math.min(dp(146), w * 0.36f);
        dropButton.set(w - sidePad - buttonW, controlRect.bottom - dp(18) - buttonH,
                w - sidePad, controlRect.bottom - dp(18));
        autoButton.set(sidePad, dropButton.top, sidePad + dp(92), dropButton.bottom);
        pauseButton.set(w - sidePad - dp(46), topRect.top + dp(8), w - sidePad, topRect.top + dp(54));
        aimTrack.set(autoButton.right + dp(14), dropButton.top + dp(10),
                dropButton.left - dp(16), dropButton.bottom - dp(10));

        pegs.clear();
        pegs.add(new Peg(0.28f, 0.35f, 0.025f));
        pegs.add(new Peg(0.50f, 0.43f, 0.025f));
        pegs.add(new Peg(0.72f, 0.35f, 0.025f));
        pegs.add(new Peg(0.38f, 0.61f, 0.022f));
        pegs.add(new Peg(0.62f, 0.61f, 0.022f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min(0.034f, Math.max(0.001f, (now - lastFrameMs) / 1000f));
        lastFrameMs = now;

        if (running && !paused) {
            update(dt);
            if (now - lastSaveMs > 15000L) {
                save();
                lastSaveMs = now;
            }
        }

        drawBackground(canvas);
        drawTopHud(canvas);
        drawTray(canvas);
        drawPusher(canvas);
        drawPegs(canvas);
        drawTokens(canvas);
        drawGates(canvas);
        drawControls(canvas);
        drawFloatTexts(canvas);
        drawParticles(canvas);
        if (paused) {
            drawPauseOverlay(canvas);
        }

        if (running) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (paused) {
                    handlePausedTap(x, y);
                    return true;
                }
                if (pauseButton.contains(x, y)) {
                    paused = true;
                    click(HapticFeedbackConstants.CONTEXT_CLICK);
                    return true;
                }
                if (autoButton.contains(x, y)) {
                    autoDrop = !autoDrop;
                    click(HapticFeedbackConstants.VIRTUAL_KEY);
                    addText(autoDrop ? "Auto on" : "Auto off", 0.18f, 0.92f, Color.WHITE);
                    return true;
                }
                if (dropButton.contains(x, y)) {
                    dropHeld = true;
                    dropCoin();
                    return true;
                }
                if (aimTrack.contains(x, y) || playRect.contains(x, y)) {
                    aimDragging = true;
                    aim = normalizedAim(x);
                    if (playRect.contains(x, y)) {
                        dropCoin();
                    }
                    return true;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (aimDragging) {
                    aim = normalizedAim(x);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                dropHeld = false;
                aimDragging = false;
                return true;
            default:
                return true;
        }
    }

    private void handlePausedTap(float x, float y) {
        float centerX = getWidth() * 0.5f;
        float top = getHeight() * 0.34f;
        RectF resume = new RectF(centerX - dp(130), top + dp(72), centerX + dp(130), top + dp(126));
        RectF sound = new RectF(centerX - dp(130), top + dp(138), centerX + dp(130), top + dp(192));
        RectF reset = new RectF(centerX - dp(130), top + dp(204), centerX + dp(130), top + dp(258));
        if (resume.contains(x, y)) {
            paused = false;
            click(HapticFeedbackConstants.CONTEXT_CLICK);
        } else if (sound.contains(x, y)) {
            soundEnabled = !soundEnabled;
            click(HapticFeedbackConstants.VIRTUAL_KEY);
        } else if (reset.contains(x, y)) {
            resetProgress();
            paused = false;
            click(HapticFeedbackConstants.LONG_PRESS);
        }
    }

    private void update(float dt) {
        pusherTime += dt * 1.12f;
        bonusGateTime += dt;
        previousPusherY = pusherY;
        pusherY = 0.205f + (float) ((Math.sin(pusherTime * Math.PI * 2.0 - Math.PI / 2.0) + 1.0) * 0.097);
        pusherVelocity = (pusherY - previousPusherY) / Math.max(dt, 0.001f);
        dropCooldown = Math.max(0f, dropCooldown - dt);
        refillClock += dt;

        if ((autoDrop || dropHeld) && dropCooldown <= 0f) {
            dropCoin();
        }

        if (coinsBank < 18 && refillClock > 75f) {
            coinsBank += 65 + level * 3;
            serviceRefills++;
            refillClock = 0f;
            addText("Refill +" + (65 + level * 3), 0.5f, 0.2f, Color.rgb(164, 229, 255));
        }

        updateTokens(dt);
        updateFloatTexts(dt);
        updateParticles(dt);
        maybeSpawnCollectible(dt);
    }

    private void updateTokens(float dt) {
        for (Token token : tokens) {
            token.age += dt;
            token.x += token.vx * dt;
            token.y += token.vy * dt;
            token.vx *= 0.985f;
            token.vy *= 0.982f;
            token.vy += 0.012f * dt;

            if (token.y < pusherY + token.r + 0.016f && token.y > pusherY - 0.082f && pusherVelocity > 0f) {
                token.y = pusherY + token.r + 0.016f;
                token.vy += Math.min(1.3f, pusherVelocity * 0.62f);
                token.vx += (token.x - 0.5f) * 0.005f;
            }

            for (Peg peg : pegs) {
                float dx = token.x - peg.x;
                float dy = token.y - peg.y;
                float min = token.r + peg.r;
                float dist2 = dx * dx + dy * dy;
                if (dist2 > 0f && dist2 < min * min) {
                    float dist = (float) Math.sqrt(dist2);
                    float nx = dx / dist;
                    float ny = dy / dist;
                    float overlap = min - dist;
                    token.x += nx * overlap;
                    token.y += ny * overlap;
                    float dot = token.vx * nx + token.vy * ny;
                    if (dot < 0f) {
                        token.vx -= dot * nx * 1.25f;
                        token.vy -= dot * ny * 1.25f;
                    }
                    if (token.bumpCooldown <= 0f) {
                        progressObjective(EVENT_BUMPER, 1);
                        token.bumpCooldown = 0.4f;
                        if (random.nextFloat() < 0.28f) {
                            addParticle(token.x, token.y, Color.rgb(220, 232, 238));
                        }
                    }
                }
            }
            token.bumpCooldown = Math.max(0f, token.bumpCooldown - dt);
        }

        for (int i = 0; i < tokens.size(); i++) {
            Token a = tokens.get(i);
            for (int j = i + 1; j < tokens.size(); j++) {
                Token b = tokens.get(j);
                resolveTokenCollision(a, b);
            }
        }

        Iterator<Token> iterator = tokens.iterator();
        while (iterator.hasNext()) {
            Token token = iterator.next();
            if (token.x < token.r) {
                token.x = token.r;
                token.vx = Math.abs(token.vx) * 0.38f;
            } else if (token.x > 1f - token.r) {
                token.x = 1f - token.r;
                token.vx = -Math.abs(token.vx) * 0.38f;
            }
            if (token.y < token.r) {
                token.y = token.r;
                token.vy = Math.abs(token.vy) * 0.2f;
            }

            boolean sideFall = token.y > 0.42f && (token.x < 0.055f || token.x > 0.945f)
                    && Math.abs(token.vx) > 0.012f && random.nextFloat() < 0.018f;
            if (sideFall) {
                processSideFall(token);
                iterator.remove();
            } else if (token.y > 1.035f) {
                processFrontFall(token);
                iterator.remove();
            }
        }

        while (tokens.size() > 128) {
            tokens.remove(0);
        }
    }

    private void resolveTokenCollision(Token a, Token b) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        float min = a.r + b.r;
        float dist2 = dx * dx + dy * dy;
        if (dist2 <= 0f || dist2 >= min * min) {
            return;
        }
        float dist = (float) Math.sqrt(dist2);
        float nx = dx / dist;
        float ny = dy / dist;
        float overlap = min - dist;
        float pushA = b.mass / (a.mass + b.mass);
        float pushB = a.mass / (a.mass + b.mass);
        a.x -= nx * overlap * pushA;
        a.y -= ny * overlap * pushA;
        b.x += nx * overlap * pushB;
        b.y += ny * overlap * pushB;

        float rvx = b.vx - a.vx;
        float rvy = b.vy - a.vy;
        float contactSpeed = rvx * nx + rvy * ny;
        if (contactSpeed < 0f) {
            float impulse = -(1.08f * contactSpeed) / (1f / a.mass + 1f / b.mass);
            a.vx -= impulse * nx / a.mass;
            a.vy -= impulse * ny / a.mass;
            b.vx += impulse * nx / b.mass;
            b.vy += impulse * ny / b.mass;
        }
    }

    private void processFrontFall(Token token) {
        lifetimeFrontFalls++;
        int coinReward = 0;
        int ticketReward = 0;
        int messageColor = Color.rgb(246, 205, 87);
        String message;

        if (token.kind == TOKEN_CARD) {
            collectCard(token.card);
            progressObjective(EVENT_CARD, 1);
            message = "Card " + (token.card + 1);
            messageColor = Color.rgb(136, 190, 255);
        } else if (token.kind == TOKEN_CHIP) {
            coinReward = 8 + level;
            ticketReward = 55 + level * 3;
            coinsBank += coinReward;
            tickets += ticketReward;
            xp += 7;
            progressObjective(EVENT_CHIP, 1);
            message = "+" + coinReward + " coins +" + ticketReward;
            messageColor = Color.rgb(132, 230, 190);
        } else if (token.kind == TOKEN_MEDAL) {
            coinReward = 20 + level * 2;
            ticketReward = 110 + level * 8;
            coinsBank += coinReward;
            tickets += ticketReward;
            xp += 12;
            message = "Medal +" + coinReward;
            messageColor = Color.rgb(255, 156, 118);
        } else {
            float gateX = bonusGateX();
            float dx = Math.abs(token.x - gateX);
            if (dx < 0.055f || jackpotMeter >= 1f && token.x > 0.42f && token.x < 0.58f) {
                int jackpotCoins = 90 + level * 12 + (int) (jackpotMeter * 60);
                int jackpotTickets = 650 + level * 45;
                coinsBank += jackpotCoins;
                tickets += jackpotTickets;
                xp += 35;
                jackpotWins++;
                jackpotMeter = 0.16f;
                progressObjective(EVENT_JACKPOT, 1);
                burst(token.x, 0.94f, Color.rgb(255, 221, 90), 18);
                message = "Jackpot +" + jackpotCoins;
                messageColor = Color.rgb(255, 221, 90);
                playTone(ToneGenerator.TONE_PROP_ACK, 110);
            } else if (token.x < 0.18f || token.x > 0.82f) {
                coinReward = 1;
                coinsBank += coinReward;
                xp += 1;
                message = "+1";
            } else if (token.x < 0.36f) {
                coinReward = 3 + level / 3;
                ticketReward = 18 + level * 2;
                coinsBank += coinReward;
                tickets += ticketReward;
                xp += 3;
                message = "+" + coinReward + " +" + ticketReward;
                messageColor = Color.rgb(135, 215, 255);
            } else if (token.x < 0.64f) {
                coinReward = 5 + level / 2;
                ticketReward = 25 + level * 2;
                coinsBank += coinReward;
                tickets += ticketReward;
                xp += 4;
                message = "+" + coinReward + " coins";
                messageColor = Color.rgb(255, 223, 118);
            } else {
                coinReward = 2 + level / 3;
                ticketReward = 42 + level * 3;
                coinsBank += coinReward;
                tickets += ticketReward;
                xp += 4;
                message = "+" + ticketReward + " tickets";
                messageColor = Color.rgb(166, 230, 185);
            }
            progressObjective(EVENT_FRONT, 1);
        }

        addText(message, token.x, 0.9f, messageColor);
        while (xp >= xpNeeded()) {
            xp -= xpNeeded();
            level++;
            coinsBank += 35 + level * 3;
            tickets += 125 + level * 12;
            addText("Level " + level, 0.5f, 0.28f, Color.rgb(255, 255, 255));
            burst(0.5f, 0.28f, Color.rgb(128, 204, 255), 16);
        }
    }

    private void processSideFall(Token token) {
        if (token.kind == TOKEN_CARD || token.kind == TOKEN_CHIP || token.kind == TOKEN_MEDAL) {
            tickets += 12;
            addText("+12 side", token.x, token.y, Color.rgb(180, 190, 198));
        } else {
            addText("miss", token.x, token.y, Color.rgb(150, 158, 164));
        }
    }

    private void collectCard(int index) {
        if (index < 0 || index >= cardCounts.length) {
            return;
        }
        cardCounts[index]++;
        boolean setComplete = true;
        for (int count : cardCounts) {
            if (count <= 0) {
                setComplete = false;
                break;
            }
        }
        if (setComplete) {
            for (int i = 0; i < cardCounts.length; i++) {
                cardCounts[i]--;
            }
            cardSets++;
            int coinReward = 170 + level * 9;
            int ticketReward = 1200 + level * 80;
            coinsBank += coinReward;
            tickets += ticketReward;
            xp += 55;
            addText("Set +" + coinReward, 0.5f, 0.18f, Color.rgb(140, 190, 255));
            burst(0.5f, 0.18f, Color.rgb(132, 190, 255), 24);
        } else if (cardCounts[index] > 1) {
            tickets += 45 + level * 4;
        }
        playTone(ToneGenerator.TONE_PROP_BEEP2, 70);
    }

    private void maybeSpawnCollectible(float dt) {
        float chance = dt * (0.035f + level * 0.0016f);
        if (random.nextFloat() > chance || tokens.size() > 118) {
            return;
        }
        int kind;
        float roll = random.nextFloat();
        if (roll < 0.42f) {
            kind = TOKEN_CHIP;
        } else if (roll < 0.76f) {
            kind = TOKEN_CARD;
        } else {
            kind = TOKEN_MEDAL;
        }
        Token token = new Token(kind, 0.18f + random.nextFloat() * 0.64f, 0.16f + random.nextFloat() * 0.16f);
        if (kind == TOKEN_CARD) {
            token.card = random.nextInt(cardCounts.length);
        }
        tokens.add(token);
    }

    private void dropCoin() {
        if (dropCooldown > 0f) {
            return;
        }
        if (coinsBank <= 0) {
            addText("Refill soon", 0.5f, 0.16f, Color.rgb(180, 210, 226));
            refillClock = Math.max(refillClock, 72f);
            dropCooldown = 0.35f;
            return;
        }
        coinsBank--;
        lifetimeDrops++;
        jackpotMeter = Math.min(1.25f, jackpotMeter + 0.0058f + level * 0.00005f);
        float chuteWobble = (random.nextFloat() - 0.5f) * 0.018f;
        Token token = new Token(TOKEN_COIN, clamp(aim + chuteWobble, 0.08f, 0.92f), 0.04f);
        token.vy = 0.18f + random.nextFloat() * 0.045f;
        token.vx = (random.nextFloat() - 0.5f) * 0.012f;
        tokens.add(token);
        dropCooldown = autoDrop || dropHeld ? 0.22f : 0.12f;
        progressObjective(EVENT_DROP, 1);
        click(HapticFeedbackConstants.KEYBOARD_TAP);
        playTone(ToneGenerator.TONE_PROP_BEEP, 35);
    }

    private void seedTrayIfNeeded() {
        if (coinsBank <= 0) {
            coinsBank = 180;
            tickets = 0;
            level = 1;
            xp = 0;
            jackpotMeter = 0.35f;
        }
        while (objectives.size() < 3) {
            objectives.add(newObjective(objectives.size()));
        }
        if (!tokens.isEmpty()) {
            return;
        }
        for (int row = 0; row < 7; row++) {
            int count = row % 2 == 0 ? 7 : 6;
            float y = 0.32f + row * 0.075f;
            for (int col = 0; col < count; col++) {
                float x = (col + 1f) / (count + 1f) + (random.nextFloat() - 0.5f) * 0.028f;
                Token coin = new Token(TOKEN_COIN, clamp(x, 0.07f, 0.93f), y + (random.nextFloat() - 0.5f) * 0.018f);
                coin.vx = (random.nextFloat() - 0.5f) * 0.01f;
                coin.vy = (random.nextFloat() - 0.5f) * 0.01f;
                tokens.add(coin);
            }
        }
        for (int i = 0; i < 4; i++) {
            Token chip = new Token(i == 0 ? TOKEN_CARD : TOKEN_CHIP, 0.18f + random.nextFloat() * 0.64f, 0.22f + random.nextFloat() * 0.24f);
            chip.card = random.nextInt(cardCounts.length);
            tokens.add(chip);
        }
    }

    private Objective newObjective(int slot) {
        int selector = (slot + level + random.nextInt(5)) % 6;
        switch (selector) {
            case 0:
                return new Objective(EVENT_DROP, 26 + level * 2, 18 + level * 2, 70 + level * 8, "Drop coins");
            case 1:
                return new Objective(EVENT_FRONT, 12 + level, 24 + level * 2, 95 + level * 11, "Win falls");
            case 2:
                return new Objective(EVENT_CARD, 2 + Math.min(3, level / 4), 38 + level * 3, 150 + level * 16, "Collect cards");
            case 3:
                return new Objective(EVENT_CHIP, 3 + Math.min(4, level / 3), 34 + level * 3, 130 + level * 12, "Collect chips");
            case 4:
                return new Objective(EVENT_BUMPER, 18 + level * 2, 20 + level * 2, 85 + level * 9, "Hit pegs");
            default:
                return new Objective(EVENT_JACKPOT, 1, 90 + level * 5, 420 + level * 38, "Jackpot");
        }
    }

    private void progressObjective(int event, int amount) {
        for (int i = 0; i < objectives.size(); i++) {
            Objective objective = objectives.get(i);
            if (objective.event != event) {
                continue;
            }
            objective.progress += amount;
            if (objective.progress >= objective.target) {
                coinsBank += objective.rewardCoins;
                tickets += objective.rewardTickets;
                xp += 16 + level * 2;
                addText("Goal +" + objective.rewardCoins, 0.5f, 0.13f + 0.04f * i, Color.rgb(238, 245, 250));
                burst(0.5f, 0.18f + 0.04f * i, Color.rgb(238, 245, 250), 10);
                objectives.set(i, newObjective(i + lifetimeDrops));
            }
        }
    }

    private void resetProgress() {
        tokens.clear();
        floatTexts.clear();
        particles.clear();
        objectives.clear();
        for (int i = 0; i < cardCounts.length; i++) {
            cardCounts[i] = 0;
        }
        coinsBank = 180;
        tickets = 0;
        level = 1;
        xp = 0;
        jackpotMeter = 0.35f;
        jackpotWins = 0;
        cardSets = 0;
        lifetimeDrops = 0;
        lifetimeFrontFalls = 0;
        serviceRefills = 0;
        autoDrop = false;
        seedTrayIfNeeded();
        save();
    }

    private void drawBackground(Canvas canvas) {
        paint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                Color.rgb(13, 16, 18), Color.rgb(35, 38, 40), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
    }

    private void drawTopHud(Canvas canvas) {
        paint.setShader(new LinearGradient(0, 0, 0, topRect.bottom,
                Color.rgb(28, 32, 35), Color.rgb(16, 18, 20), Shader.TileMode.CLAMP));
        canvas.drawRect(topRect, paint);
        paint.setShader(null);

        float pad = dp(12);
        float y = dp(23);
        drawMetric(canvas, pad, y, "COINS", String.valueOf(coinsBank), Color.rgb(244, 200, 82));
        drawMetric(canvas, getWidth() * 0.28f, y, "TICKETS", compact(tickets), Color.rgb(128, 215, 244));
        drawMetric(canvas, getWidth() * 0.57f, y, "LEVEL", String.valueOf(level), Color.rgb(235, 238, 241));

        float meterLeft = pad;
        float meterTop = topRect.bottom - dp(30);
        float meterRight = getWidth() - dp(62);
        float meterBottom = topRect.bottom - dp(13);
        drawMeter(canvas, meterLeft, meterTop, meterRight, meterBottom, Math.min(1f, jackpotMeter),
                Color.rgb(245, 188, 72), "JACKPOT");

        drawPauseIcon(canvas, pauseButton);
    }

    private void drawMetric(Canvas canvas, float x, float y, String label, String value, int color) {
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
        textPaint.setTextSize(sp(10));
        textPaint.setColor(Color.rgb(154, 164, 171));
        canvas.drawText(label, x, y, textPaint);
        textPaint.setTextSize(sp(18));
        textPaint.setColor(color);
        canvas.drawText(value, x, y + dp(21), textPaint);
    }

    private void drawMeter(Canvas canvas, float left, float top, float right, float bottom, float fraction, int color, String label) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(42, 47, 51));
        tmpRect.set(left, top, right, bottom);
        canvas.drawRoundRect(tmpRect, dp(8), dp(8), paint);
        tmpRect.set(left, top, left + (right - left) * clamp(fraction, 0f, 1f), bottom);
        paint.setColor(color);
        canvas.drawRoundRect(tmpRect, dp(8), dp(8), paint);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(sp(10));
        textPaint.setColor(Color.rgb(28, 30, 31));
        canvas.drawText(label, left + dp(8), bottom - dp(4), textPaint);
    }

    private void drawTray(Canvas canvas) {
        float leftBack = playRect.left + playRect.width() * 0.105f;
        float rightBack = playRect.right - playRect.width() * 0.105f;
        float leftFront = playRect.left + playRect.width() * 0.025f;
        float rightFront = playRect.right - playRect.width() * 0.025f;

        trayPath.reset();
        trayPath.moveTo(leftBack, playRect.top);
        trayPath.lineTo(rightBack, playRect.top);
        trayPath.lineTo(rightFront, playRect.bottom);
        trayPath.lineTo(leftFront, playRect.bottom);
        trayPath.close();

        paint.setShader(new LinearGradient(0, playRect.top, 0, playRect.bottom,
                Color.rgb(76, 83, 87), Color.rgb(29, 32, 35), Shader.TileMode.CLAMP));
        canvas.drawPath(trayPath, paint);
        paint.setShader(null);

        strokePaint.setStrokeWidth(dp(2));
        strokePaint.setColor(Color.rgb(132, 140, 145));
        canvas.drawPath(trayPath, strokePaint);

        paint.setColor(Color.argb(75, 255, 255, 255));
        for (int i = 1; i < 5; i++) {
            float y = playRect.top + playRect.height() * (i / 5f);
            canvas.drawLine(laneLeft(i / 5f), y, laneRight(i / 5f), y, paint);
        }

        drawObjectives(canvas);
    }

    private void drawObjectives(Canvas canvas) {
        float left = playRect.left + dp(8);
        float top = playRect.top + dp(8);
        float width = Math.min(playRect.width() - dp(16), dp(320));
        float rowH = dp(20);
        for (int i = 0; i < objectives.size(); i++) {
            Objective objective = objectives.get(i);
            float y = top + i * (rowH + dp(4));
            paint.setColor(Color.argb(155, 20, 24, 27));
            tmpRect.set(left, y, left + width, y + rowH);
            canvas.drawRoundRect(tmpRect, dp(6), dp(6), paint);
            float progress = clamp(objective.progress / (float) objective.target, 0f, 1f);
            paint.setColor(Color.argb(210, 82, 130, 145));
            tmpRect.set(left, y, left + width * progress, y + rowH);
            canvas.drawRoundRect(tmpRect, dp(6), dp(6), paint);
            textPaint.setTextAlign(Paint.Align.LEFT);
            textPaint.setTextSize(sp(10));
            textPaint.setColor(Color.WHITE);
            canvas.drawText(objective.label + " " + objective.progress + "/" + objective.target,
                    left + dp(7), y + dp(14), textPaint);
        }
    }

    private void drawPusher(Canvas canvas) {
        float frontY = worldY(pusherY);
        float backY = worldY(Math.max(0.055f, pusherY - 0.13f));
        float left = laneLeft(pusherY) + playRect.width() * 0.035f;
        float right = laneRight(pusherY) - playRect.width() * 0.035f;
        paint.setShader(new LinearGradient(0, backY, 0, frontY,
                Color.rgb(168, 176, 181), Color.rgb(78, 86, 92), Shader.TileMode.CLAMP));
        tmpRect.set(left, backY, right, frontY);
        canvas.drawRoundRect(tmpRect, dp(8), dp(8), paint);
        paint.setShader(null);
        strokePaint.setStrokeWidth(dp(2));
        strokePaint.setColor(Color.rgb(210, 216, 220));
        canvas.drawLine(left + dp(6), frontY, right - dp(6), frontY, strokePaint);

        float slotX = worldX(aim, 0.04f);
        strokePaint.setStrokeWidth(dp(3));
        strokePaint.setColor(Color.rgb(245, 210, 93));
        canvas.drawLine(slotX, playRect.top + dp(6), slotX, worldY(0.15f), strokePaint);
        paint.setColor(Color.rgb(245, 210, 93));
        canvas.drawCircle(slotX, playRect.top + dp(10), dp(5), paint);
    }

    private void drawPegs(Canvas canvas) {
        for (Peg peg : pegs) {
            float x = worldX(peg.x, peg.y);
            float y = worldY(peg.y);
            float r = worldRadius(peg.r, peg.y);
            paint.setShader(new RadialGradient(x - r * 0.3f, y - r * 0.4f, r,
                    Color.rgb(235, 240, 242), Color.rgb(94, 105, 111), Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, paint);
            paint.setShader(null);
        }
    }

    private void drawTokens(Canvas canvas) {
        List<Token> sorted = new ArrayList<>(tokens);
        Collections.sort(sorted, new Comparator<Token>() {
            @Override
            public int compare(Token a, Token b) {
                return Float.compare(a.y, b.y);
            }
        });
        for (Token token : sorted) {
            drawToken(canvas, token);
        }
    }

    private void drawToken(Canvas canvas, Token token) {
        float x = worldX(token.x, token.y);
        float y = worldY(token.y);
        float r = worldRadius(token.r, token.y);
        paint.setStyle(Paint.Style.FILL);
        if (token.kind == TOKEN_COIN) {
            paint.setColor(Color.argb(85, 0, 0, 0));
            canvas.drawOval(x - r * 0.95f, y + r * 0.42f, x + r * 0.95f, y + r * 0.88f, paint);
            paint.setShader(new RadialGradient(x - r * 0.25f, y - r * 0.35f, r * 1.2f,
                    Color.rgb(255, 224, 118), Color.rgb(170, 116, 36), Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, paint);
            paint.setShader(null);
            strokePaint.setStrokeWidth(Math.max(1f, r * 0.08f));
            strokePaint.setColor(Color.rgb(122, 88, 36));
            canvas.drawCircle(x, y, r * 0.68f, strokePaint);
        } else if (token.kind == TOKEN_CHIP) {
            paint.setColor(Color.rgb(56, 184, 139));
            canvas.drawCircle(x, y, r * 1.08f, paint);
            strokePaint.setStrokeWidth(dp(2));
            strokePaint.setColor(Color.WHITE);
            canvas.drawCircle(x, y, r * 0.67f, strokePaint);
        } else if (token.kind == TOKEN_CARD) {
            paint.setColor(Color.rgb(72, 132, 210));
            tmpRect.set(x - r * 0.95f, y - r * 1.18f, x + r * 0.95f, y + r * 1.18f);
            canvas.save();
            canvas.rotate((token.card - 2.5f) * 4f, x, y);
            canvas.drawRoundRect(tmpRect, r * 0.22f, r * 0.22f, paint);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(r * 0.9f);
            textPaint.setColor(Color.WHITE);
            canvas.drawText(String.valueOf(token.card + 1), x, y + r * 0.32f, textPaint);
            canvas.restore();
        } else {
            paint.setColor(Color.rgb(232, 112, 82));
            canvas.drawCircle(x, y, r * 1.08f, paint);
            paint.setColor(Color.rgb(255, 232, 188));
            canvas.drawCircle(x, y, r * 0.56f, paint);
        }
    }

    private void drawGates(Canvas canvas) {
        float top = playRect.bottom - dp(34);
        float bottom = playRect.bottom - dp(6);
        float[] cuts = {0f, 0.18f, 0.36f, 0.64f, 0.82f, 1f};
        int[] colors = {
                Color.rgb(58, 63, 67),
                Color.rgb(52, 98, 122),
                Color.rgb(145, 107, 42),
                Color.rgb(61, 121, 83),
                Color.rgb(58, 63, 67)
        };
        String[] labels = {"1", "T", "J", "B", "1"};
        for (int i = 0; i < colors.length; i++) {
            float left = laneLeft(1f) + (laneRight(1f) - laneLeft(1f)) * cuts[i];
            float right = laneLeft(1f) + (laneRight(1f) - laneLeft(1f)) * cuts[i + 1];
            paint.setColor(colors[i]);
            tmpRect.set(left + dp(1), top, right - dp(1), bottom);
            canvas.drawRoundRect(tmpRect, dp(5), dp(5), paint);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(sp(11));
            textPaint.setColor(Color.WHITE);
            canvas.drawText(labels[i], (left + right) * 0.5f, bottom - dp(8), textPaint);
        }

        float gx = worldX(bonusGateX(), 0.985f);
        paint.setColor(Color.rgb(255, 226, 101));
        canvas.drawRoundRect(gx - dp(15), top - dp(6), gx + dp(15), bottom + dp(3), dp(6), dp(6), paint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(sp(10));
        textPaint.setColor(Color.rgb(32, 30, 22));
        canvas.drawText("JP", gx, bottom - dp(7), textPaint);
    }

    private void drawControls(Canvas canvas) {
        paint.setShader(new LinearGradient(0, controlRect.top, 0, controlRect.bottom,
                Color.rgb(24, 28, 31), Color.rgb(12, 14, 16), Shader.TileMode.CLAMP));
        canvas.drawRect(controlRect, paint);
        paint.setShader(null);

        drawCardProgress(canvas);
        drawAimTrack(canvas);
        drawButton(canvas, autoButton, autoDrop ? "AUTO" : "MANUAL", autoDrop ? Color.rgb(70, 134, 116) : Color.rgb(49, 55, 59));
        drawButton(canvas, dropButton, "DROP", coinsBank > 0 ? Color.rgb(205, 157, 55) : Color.rgb(85, 88, 91));
    }

    private void drawCardProgress(Canvas canvas) {
        float left = dp(13);
        float top = controlRect.top + dp(10);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setTextSize(sp(10));
        textPaint.setColor(Color.rgb(150, 160, 168));
        canvas.drawText("SET " + cardSets, left, top + dp(10), textPaint);
        float start = left + dp(54);
        float size = Math.min(dp(28), (getWidth() - start - dp(18)) / 7f);
        for (int i = 0; i < cardCounts.length; i++) {
            float x = start + i * (size + dp(5));
            tmpRect.set(x, top, x + size, top + size * 1.22f);
            paint.setColor(cardCounts[i] > 0 ? Color.rgb(78, 134, 210) : Color.rgb(45, 50, 54));
            canvas.drawRoundRect(tmpRect, dp(4), dp(4), paint);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(sp(11));
            textPaint.setColor(Color.WHITE);
            canvas.drawText(String.valueOf(i + 1), x + size / 2f, top + size * 0.78f, textPaint);
        }
    }

    private void drawAimTrack(Canvas canvas) {
        paint.setColor(Color.rgb(37, 42, 46));
        canvas.drawRoundRect(aimTrack, dp(12), dp(12), paint);
        strokePaint.setStrokeWidth(dp(2));
        strokePaint.setColor(Color.rgb(94, 104, 111));
        canvas.drawLine(aimTrack.left + dp(16), aimTrack.centerY(), aimTrack.right - dp(16), aimTrack.centerY(), strokePaint);
        float knobX = aimTrack.left + aimTrack.width() * aim;
        paint.setColor(Color.rgb(238, 211, 116));
        canvas.drawCircle(knobX, aimTrack.centerY(), dp(16), paint);
        paint.setColor(Color.rgb(34, 36, 38));
        canvas.drawCircle(knobX, aimTrack.centerY(), dp(5), paint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(sp(10));
        textPaint.setColor(Color.rgb(174, 183, 189));
        canvas.drawText("AIM", aimTrack.centerX(), aimTrack.top - dp(5), textPaint);
    }

    private void drawButton(Canvas canvas, RectF rect, String label, int color) {
        paint.setColor(color);
        canvas.drawRoundRect(rect, dp(12), dp(12), paint);
        paint.setColor(Color.argb(70, 255, 255, 255));
        canvas.drawRoundRect(rect.left + dp(3), rect.top + dp(3), rect.right - dp(3), rect.centerY(), dp(10), dp(10), paint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
        textPaint.setTextSize(rect.width() > dp(100) ? sp(18) : sp(12));
        textPaint.setColor(Color.WHITE);
        canvas.drawText(label, rect.centerX(), rect.centerY() + dp(7), textPaint);
    }

    private void drawPauseIcon(Canvas canvas, RectF rect) {
        paint.setColor(Color.argb(160, 42, 47, 51));
        canvas.drawRoundRect(rect, dp(10), dp(10), paint);
        paint.setColor(Color.rgb(232, 236, 238));
        float w = rect.width();
        canvas.drawRoundRect(rect.left + w * 0.34f, rect.top + w * 0.25f,
                rect.left + w * 0.44f, rect.bottom - w * 0.25f, dp(2), dp(2), paint);
        canvas.drawRoundRect(rect.left + w * 0.56f, rect.top + w * 0.25f,
                rect.left + w * 0.66f, rect.bottom - w * 0.25f, dp(2), dp(2), paint);
    }

    private void drawFloatTexts(Canvas canvas) {
        for (FloatText text : floatTexts) {
            float alpha = clamp(text.life / text.maxLife, 0f, 1f);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD));
            textPaint.setTextSize(sp(15 + text.scale));
            textPaint.setColor(applyAlpha(text.color, alpha));
            canvas.drawText(text.message, worldX(text.x, text.y), worldY(text.y) - (1f - alpha) * dp(34), textPaint);
        }
    }

    private void drawParticles(Canvas canvas) {
        for (Particle particle : particles) {
            float alpha = clamp(particle.life / particle.maxLife, 0f, 1f);
            paint.setColor(applyAlpha(particle.color, alpha));
            canvas.drawCircle(worldX(particle.x, particle.y), worldY(particle.y), dp(2.2f) * alpha + dp(0.8f), paint);
        }
    }

    private void drawPauseOverlay(Canvas canvas) {
        paint.setColor(Color.argb(205, 0, 0, 0));
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        float centerX = getWidth() * 0.5f;
        float top = getHeight() * 0.34f;
        tmpRect.set(centerX - dp(154), top, centerX + dp(154), top + dp(286));
        paint.setColor(Color.rgb(28, 32, 35));
        canvas.drawRoundRect(tmpRect, dp(14), dp(14), paint);
        strokePaint.setStrokeWidth(dp(1));
        strokePaint.setColor(Color.rgb(83, 93, 100));
        canvas.drawRoundRect(tmpRect, dp(14), dp(14), strokePaint);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(sp(22));
        textPaint.setColor(Color.WHITE);
        canvas.drawText("CoinPush", centerX, top + dp(42), textPaint);

        RectF resume = new RectF(centerX - dp(130), top + dp(72), centerX + dp(130), top + dp(126));
        RectF sound = new RectF(centerX - dp(130), top + dp(138), centerX + dp(130), top + dp(192));
        RectF reset = new RectF(centerX - dp(130), top + dp(204), centerX + dp(130), top + dp(258));
        drawButton(canvas, resume, "RESUME", Color.rgb(60, 116, 130));
        drawButton(canvas, sound, soundEnabled ? "SOUND ON" : "SOUND OFF", Color.rgb(58, 64, 69));
        drawButton(canvas, reset, "RESET", Color.rgb(113, 58, 50));
    }

    private void updateFloatTexts(float dt) {
        Iterator<FloatText> iterator = floatTexts.iterator();
        while (iterator.hasNext()) {
            FloatText text = iterator.next();
            text.life -= dt;
            text.y -= dt * 0.055f;
            if (text.life <= 0f) {
                iterator.remove();
            }
        }
    }

    private void updateParticles(float dt) {
        Iterator<Particle> iterator = particles.iterator();
        while (iterator.hasNext()) {
            Particle particle = iterator.next();
            particle.life -= dt;
            particle.x += particle.vx * dt;
            particle.y += particle.vy * dt;
            particle.vy += 0.42f * dt;
            if (particle.life <= 0f) {
                iterator.remove();
            }
        }
    }

    private void addText(String message, float x, float y, int color) {
        if (floatTexts.size() > 24) {
            floatTexts.remove(0);
        }
        floatTexts.add(new FloatText(message, x, y, color));
    }

    private void addParticle(float x, float y, int color) {
        Particle particle = new Particle();
        particle.x = x;
        particle.y = y;
        particle.vx = (random.nextFloat() - 0.5f) * 0.3f;
        particle.vy = -random.nextFloat() * 0.22f;
        particle.life = particle.maxLife = 0.42f + random.nextFloat() * 0.34f;
        particle.color = color;
        particles.add(particle);
    }

    private void burst(float x, float y, int color, int count) {
        for (int i = 0; i < count; i++) {
            addParticle(x, y, color);
        }
    }

    private void playTone(int toneType, int durationMs) {
        if (soundEnabled && tone != null) {
            try {
                tone.startTone(toneType, durationMs);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void click(int haptic) {
        performHapticFeedback(haptic);
    }

    private float normalizedAim(float screenX) {
        float left = aimTrack.width() > 1f ? aimTrack.left : playRect.left;
        float width = aimTrack.width() > 1f ? aimTrack.width() : playRect.width();
        return clamp((screenX - left) / width, 0.06f, 0.94f);
    }

    private float bonusGateX() {
        return 0.5f + (float) Math.sin(bonusGateTime * 1.65f) * 0.25f;
    }

    private float laneLeft(float y) {
        return playRect.left + playRect.width() * (0.105f - 0.08f * y);
    }

    private float laneRight(float y) {
        return playRect.right - playRect.width() * (0.105f - 0.08f * y);
    }

    private float worldX(float x, float y) {
        return laneLeft(y) + (laneRight(y) - laneLeft(y)) * x;
    }

    private float worldY(float y) {
        return playRect.top + playRect.height() * y;
    }

    private float worldRadius(float r, float y) {
        return (laneRight(y) - laneLeft(y)) * r * (0.95f + y * 0.24f);
    }

    private int xpNeeded() {
        return 88 + level * 18;
    }

    private float dp(float value) {
        return value * density;
    }

    private float sp(float value) {
        return value * textScale;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int applyAlpha(int color, float alpha) {
        return Color.argb((int) (Color.alpha(color) * clamp(alpha, 0f, 1f)),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    private static String compact(int value) {
        if (value >= 1000000) {
            return String.format(Locale.US, "%.1fm", value / 1000000f);
        }
        if (value >= 10000) {
            return String.format(Locale.US, "%.1fk", value / 1000f);
        }
        return String.valueOf(value);
    }

    private void save() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putInt("version", PREF_VERSION);
        editor.putInt("coins", coinsBank);
        editor.putInt("tickets", tickets);
        editor.putInt("level", level);
        editor.putInt("xp", xp);
        editor.putInt("jackpotWins", jackpotWins);
        editor.putInt("cardSets", cardSets);
        editor.putInt("drops", lifetimeDrops);
        editor.putInt("falls", lifetimeFrontFalls);
        editor.putInt("serviceRefills", serviceRefills);
        editor.putFloat("jackpotMeter", jackpotMeter);
        editor.putFloat("aim", aim);
        editor.putBoolean("sound", soundEnabled);
        for (int i = 0; i < cardCounts.length; i++) {
            editor.putInt("card" + i, cardCounts[i]);
        }
        for (int i = 0; i < objectives.size(); i++) {
            Objective objective = objectives.get(i);
            editor.putInt("objEvent" + i, objective.event);
            editor.putInt("objTarget" + i, objective.target);
            editor.putInt("objProgress" + i, objective.progress);
            editor.putInt("objCoins" + i, objective.rewardCoins);
            editor.putInt("objTickets" + i, objective.rewardTickets);
            editor.putString("objLabel" + i, objective.label);
        }
        editor.apply();
    }

    private void load() {
        int version = prefs.getInt("version", 0);
        coinsBank = prefs.getInt("coins", 180);
        tickets = prefs.getInt("tickets", 0);
        level = Math.max(1, prefs.getInt("level", 1));
        xp = prefs.getInt("xp", 0);
        jackpotWins = prefs.getInt("jackpotWins", 0);
        cardSets = prefs.getInt("cardSets", 0);
        lifetimeDrops = prefs.getInt("drops", 0);
        lifetimeFrontFalls = prefs.getInt("falls", 0);
        serviceRefills = prefs.getInt("serviceRefills", 0);
        jackpotMeter = prefs.getFloat("jackpotMeter", 0.35f);
        aim = prefs.getFloat("aim", 0.5f);
        soundEnabled = prefs.getBoolean("sound", true);
        for (int i = 0; i < cardCounts.length; i++) {
            cardCounts[i] = prefs.getInt("card" + i, 0);
        }
        objectives.clear();
        if (version == PREF_VERSION) {
            for (int i = 0; i < 3; i++) {
                int event = prefs.getInt("objEvent" + i, 0);
                int target = prefs.getInt("objTarget" + i, 0);
                if (event > 0 && target > 0) {
                    Objective objective = new Objective(event, target,
                            prefs.getInt("objCoins" + i, 25),
                            prefs.getInt("objTickets" + i, 75),
                            prefs.getString("objLabel" + i, "Goal"));
                    objective.progress = prefs.getInt("objProgress" + i, 0);
                    objectives.add(objective);
                }
            }
        }
    }

    private static final class Token {
        final int kind;
        float x;
        float y;
        float vx;
        float vy;
        float r;
        float mass;
        float age;
        float bumpCooldown;
        int card;

        Token(int kind, float x, float y) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            if (kind == TOKEN_COIN) {
                r = 0.032f;
                mass = 1f;
            } else if (kind == TOKEN_CARD) {
                r = 0.034f;
                mass = 0.84f;
            } else {
                r = 0.036f;
                mass = 1.08f;
            }
        }
    }

    private static final class Peg {
        final float x;
        final float y;
        final float r;

        Peg(float x, float y, float r) {
            this.x = x;
            this.y = y;
            this.r = r;
        }
    }

    private static final class Objective {
        final int event;
        final int target;
        final int rewardCoins;
        final int rewardTickets;
        final String label;
        int progress;

        Objective(int event, int target, int rewardCoins, int rewardTickets, String label) {
            this.event = event;
            this.target = target;
            this.rewardCoins = rewardCoins;
            this.rewardTickets = rewardTickets;
            this.label = label;
        }
    }

    private static final class FloatText {
        final String message;
        final int color;
        final float maxLife;
        final float scale;
        float x;
        float y;
        float life;

        FloatText(String message, float x, float y, int color) {
            this.message = message;
            this.x = x;
            this.y = y;
            this.color = color;
            this.maxLife = 1.08f;
            this.life = maxLife;
            this.scale = Math.min(7f, Math.max(0f, message.length() > 10 ? 0f : 4f));
        }
    }

    private static final class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float life;
        float maxLife;
        int color;
    }
}
