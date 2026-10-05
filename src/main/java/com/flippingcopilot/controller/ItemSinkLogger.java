package com.flippingcopilot.controller;

import com.flippingcopilot.model.OsrsLoginManager;
import com.flippingcopilot.model.Transaction;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.RuneLite;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;

/**
 * Evidence log for sales to the GE item sink. Records only, never books a transaction.
 *
 * A sink sale is drawn from the ge_itemsink_* varps into a slot whose offer is EMPTY, so it never reaches
 * GrandExchangeOfferChanged. This writes, per account, one JSON line for every change to the sink/tax/last-offer
 * varps, every raw offer event, every transaction Copilot infers, and a "sink" summary whenever a slot's sink state
 * changes, to ~/.runelite/sink-log/<account>.jsonl.
 */
@Slf4j
@Singleton
public class ItemSinkLogger {

    private static final File DIR = new File(RuneLite.RUNELITE_DIR, "sink-log");
    private static final int SLOTS = 8;

    private static final int[] SINK_OBJ = {
        VarPlayerID.GE_ITEMSINK_OBJ_0, VarPlayerID.GE_ITEMSINK_OBJ_1, VarPlayerID.GE_ITEMSINK_OBJ_2, VarPlayerID.GE_ITEMSINK_OBJ_3,
        VarPlayerID.GE_ITEMSINK_OBJ_4, VarPlayerID.GE_ITEMSINK_OBJ_5, VarPlayerID.GE_ITEMSINK_OBJ_6, VarPlayerID.GE_ITEMSINK_OBJ_7,
    };
    private static final int[] SINK_PRICE = {
        VarPlayerID.GE_ITEMSINK_PRICE_0, VarPlayerID.GE_ITEMSINK_PRICE_1, VarPlayerID.GE_ITEMSINK_PRICE_2, VarPlayerID.GE_ITEMSINK_PRICE_3,
        VarPlayerID.GE_ITEMSINK_PRICE_4, VarPlayerID.GE_ITEMSINK_PRICE_5, VarPlayerID.GE_ITEMSINK_PRICE_6, VarPlayerID.GE_ITEMSINK_PRICE_7,
    };
    private static final int[] SINK_PRICE_LONG = {
        VarPlayerID.GE_ITEMSINK_PRICE_LONG_0, VarPlayerID.GE_ITEMSINK_PRICE_LONG_1, VarPlayerID.GE_ITEMSINK_PRICE_LONG_2, VarPlayerID.GE_ITEMSINK_PRICE_LONG_3,
        VarPlayerID.GE_ITEMSINK_PRICE_LONG_4, VarPlayerID.GE_ITEMSINK_PRICE_LONG_5, VarPlayerID.GE_ITEMSINK_PRICE_LONG_6, VarPlayerID.GE_ITEMSINK_PRICE_LONG_7,
    };
    private static final int[] TAX_LONG = {
        VarPlayerID.GE_TAX_SLOT_LONG_0, VarPlayerID.GE_TAX_SLOT_LONG_1, VarPlayerID.GE_TAX_SLOT_LONG_2, VarPlayerID.GE_TAX_SLOT_LONG_3,
        VarPlayerID.GE_TAX_SLOT_LONG_4, VarPlayerID.GE_TAX_SLOT_LONG_5, VarPlayerID.GE_TAX_SLOT_LONG_6, VarPlayerID.GE_TAX_SLOT_LONG_7,
    };
    private static final int[] LAST_OFFER = {
        VarPlayerID.GE_LAST_OFFER_ITEM, VarPlayerID.GE_LAST_OFFER_QUANTITY, VarPlayerID.GE_LAST_OFFER_PRICE, VarPlayerID.GE_LAST_OFFER_TYPE,
    };
    private static final String[] LAST_OFFER_KEYS = {"item", "qty", "price", "type"};

    private static final Map<Integer, String> WATCHED = new HashMap<>();
    static {
        for (int i = 0; i < SLOTS; i++) {
            WATCHED.put(SINK_OBJ[i], "sink_obj_" + i);
            WATCHED.put(SINK_PRICE[i], "sink_price_" + i);
            WATCHED.put(SINK_PRICE_LONG[i], "sink_price_long_" + i);
            WATCHED.put(TAX_LONG[i], "tax_long_" + i);
        }
        for (int i = 0; i < LAST_OFFER.length; i++) {
            WATCHED.put(LAST_OFFER[i], "last_offer_" + LAST_OFFER_KEYS[i]);
        }
    }

    private final Client client;
    private final OsrsLoginManager osrsLoginManager;
    private final Gson gson;

    // last polled (obj, price_long, tax_long) per slot; null until the first poll of a session
    private final long[][] last = new long[SLOTS][];

    @Inject
    public ItemSinkLogger(Client client, OsrsLoginManager osrsLoginManager, Gson gson) {
        this.client = client;
        this.osrsLoginManager = osrsLoginManager;
        this.gson = gson;
    }

    public void onVarbitChanged(VarbitChanged e) {
        if (e.getVarbitId() != -1) {
            return;
        }
        String name = WATCHED.get(e.getVarpId());
        if (name == null) {
            return;
        }
        Map<String, Object> ev = event("varp");
        ev.put("varp", e.getVarpId());
        ev.put("name", name);
        ev.put("value", e.getValue());
        ev.put("valueLong", longVarp(e.getVarpId()));
        write(ev);
    }

    public void onOfferChanged(GrandExchangeOfferChanged e) {
        GrandExchangeOffer o = e.getOffer();
        Map<String, Object> ev = event("offer");
        ev.put("slot", e.getSlot());
        ev.put("state", o.getState().name());
        ev.put("itemId", o.getItemId());
        ev.put("totalQty", o.getTotalQuantity());
        ev.put("qtySold", o.getQuantitySold());
        ev.put("price", o.getPrice());
        ev.put("spent", o.getSpent());
        write(ev);
    }

    public void onTransaction(Transaction t) {
        Map<String, Object> ev = event("txn");
        ev.put("slot", t.getBoxId());
        ev.put("type", String.valueOf(t.getType()));
        ev.put("itemId", t.getItemId());
        ev.put("qty", t.getQuantity());
        ev.put("price", t.getPrice());
        ev.put("amountSpent", t.getAmountSpent());
        write(ev);
    }

    public void onLoginScreen() {
        Arrays.fill(last, null);
    }

    /** Polled each tick: the summary line does not depend on the order the varps arrived in. */
    public void onGameTick() {
        if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN) {
            return;
        }
        GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
        for (int i = 0; i < SLOTS; i++) {
            long[] cur = {client.getVarpValue(SINK_OBJ[i]), longVarp(SINK_PRICE_LONG[i]), longVarp(TAX_LONG[i])};
            long[] prev = last[i];
            last[i] = cur;
            // an empty slot reads -1, and its tax varp also moves on ordinary sales: only a sink item coming or going counts
            if (prev == null ? cur[0] <= 0 : Arrays.equals(prev, cur) || (prev[0] <= 0 && cur[0] <= 0)) {
                continue;
            }
            Map<String, Object> ev = event("sink");
            ev.put("slot", i);
            ev.put("firstPoll", prev == null);
            ev.put("obj", cur[0]);
            if (cur[0] > 0) {
                ev.put("item", client.getItemDefinition((int) cur[0]).getName());
            }
            ev.put("priceInt", client.getVarpValue(SINK_PRICE[i]));
            ev.put("priceLong", cur[1]);
            ev.put("taxLong", cur[2]);
            ev.put("sold", cur[0] > 0 && cur[2] < Integer.MAX_VALUE);
            if (prev != null) {
                ev.put("prev", prev);
            }
            Map<String, Object> lastOffer = new LinkedHashMap<>();
            for (int k = 0; k < LAST_OFFER.length; k++) {
                lastOffer.put(LAST_OFFER_KEYS[k], client.getVarpValue(LAST_OFFER[k]));
            }
            ev.put("lastOffer", lastOffer);
            if (offers != null && offers[i] != null) {
                GrandExchangeOffer o = offers[i];
                ev.put("slotOffer", o.getState().name() + " item=" + o.getItemId() + " " + o.getQuantitySold() + "/" + o.getTotalQuantity() + " @" + o.getPrice());
            }
            write(ev);
        }
    }

    private Map<String, Object> event(String type) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("ts", Instant.now().toString());
        ev.put("tick", client.getTickCount());
        ev.put("atLogin", osrsLoginManager.hasJustLoggedIn());
        ev.put("ev", type);
        return ev;
    }

    // RuneLite 1.13.1 types some of these as int varps, and getVarpLongValue then throws
    private long longVarp(int varp) {
        try {
            return client.getVarpLongValue(varp);
        } catch (IllegalArgumentException ex) {
            return client.getVarpValue(varp);
        }
    }

    // the login burst arrives before the local player has a name: hold it until the name is known
    private final List<Map<String, Object>> pending = new ArrayList<>();

    private void write(Map<String, Object> ev) {
        String name = osrsLoginManager.getPlayerDisplayName();
        if (name == null) {
            pending.add(ev);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> p : pending) {
            sb.append(gson.toJson(p)).append('\n');
        }
        pending.clear();
        sb.append(gson.toJson(ev)).append('\n');
        String file = name.replaceAll("[^A-Za-z0-9._-]", "_") + ".jsonl";
        try {
            DIR.mkdirs();
            Files.write(new File(DIR, file).toPath(), sb.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ex) {
            log.warn("sink log write failed", ex);
        }
    }
}
