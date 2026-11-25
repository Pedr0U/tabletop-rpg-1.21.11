package com.pedro.tabletoprpg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class SessionStore extends SavedData {

    public static final String DEFAULT_ID = "HOME";

    private static final int ID_LENGTH = 4;
    private static final String ID_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Random RANDOM = new Random();

    public record SessionInfo(String id, String name, boolean started,
                              List<UUID> masters, List<UUID> members) {

        public static final Codec<UUID> UUID_CODEC = Codec.STRING.xmap(UUID::fromString, UUID::toString);

        public static final Codec<SessionInfo> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(SessionInfo::id),
                Codec.STRING.optionalFieldOf("name", "").forGetter(SessionInfo::name),
                Codec.BOOL.optionalFieldOf("started", false).forGetter(SessionInfo::started),
                Codec.list(UUID_CODEC).optionalFieldOf("masters", List.of()).forGetter(SessionInfo::masters),
                Codec.list(UUID_CODEC).optionalFieldOf("members", List.of()).forGetter(SessionInfo::members)
        ).apply(i, SessionInfo::new));

        public SessionInfo {
            id = normalizeId(id);
            name = cleanName(name, id);
            masters = unique(masters);
            members = unique(members);
        }
    }

    public static final Codec<SessionStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, SessionInfo.CODEC)
                    .optionalFieldOf("sessions", Map.of()).forGetter(SessionStore::sessions),
            Codec.STRING.optionalFieldOf("activeSessionId", "").forGetter(SessionStore::activeId),
            Codec.unboundedMap(Codec.STRING, ThreatSheet.CODEC.listOf())
                    .optionalFieldOf("threats", Map.of()).forGetter(SessionStore::threats),
            Codec.unboundedMap(Codec.STRING, RollPreset.CODEC.listOf())
                    .optionalFieldOf("presets", Map.of()).forGetter(SessionStore::presets),
            Codec.unboundedMap(Codec.STRING, DiaryStore.Diary.CODEC)
                    .optionalFieldOf("diaries", Map.of()).forGetter(SessionStore::diaries),
            Codec.unboundedMap(Codec.STRING, SheetModel.CODEC)
                    .optionalFieldOf("sheetModels", Map.of()).forGetter(SessionStore::sheetModels)
    ).apply(i, SessionStore::new));

    private static final SavedDataType<SessionStore> TYPE = new SavedDataType<>(
            "tabletop_rpg_sessions", SessionStore::new, CODEC, DataFixTypes.LEVEL);

    private Map<String, SessionInfo> sessions;
    private String activeSessionId;
    private Map<String, List<ThreatSheet>> threats;
    private Map<String, List<RollPreset>> presets;
    private Map<String, DiaryStore.Diary> diaries;
    private Map<String, SheetModel> sheetModels;

    public SessionStore() {
        this(new LinkedHashMap<>(), "", Map.of(), Map.of(), Map.of(), Map.of());
    }

    public SessionStore(Map<String, SessionInfo> sessions, String activeSessionId) {
        this(sessions, activeSessionId, Map.of(), Map.of(), Map.of(), Map.of());
    }

    public SessionStore(Map<String, SessionInfo> sessions, String activeSessionId,
                        Map<String, List<ThreatSheet>> threats) {
        this(sessions, activeSessionId, threats, Map.of(), Map.of(), Map.of());
    }

    public SessionStore(Map<String, SessionInfo> sessions, String activeSessionId,
                        Map<String, List<ThreatSheet>> threats,
                        Map<String, List<RollPreset>> presets) {
        this(sessions, activeSessionId, threats, presets, Map.of(), Map.of());
    }

    public SessionStore(Map<String, SessionInfo> sessions, String activeSessionId,
                        Map<String, List<ThreatSheet>> threats,
                        Map<String, List<RollPreset>> presets,
                        Map<String, DiaryStore.Diary> diaries) {
        this(sessions, activeSessionId, threats, presets, diaries, Map.of());
    }

    public SessionStore(Map<String, SessionInfo> sessions, String activeSessionId,
                        Map<String, List<ThreatSheet>> threats,
                        Map<String, List<RollPreset>> presets,
                        Map<String, DiaryStore.Diary> diaries,
                        Map<String, SheetModel> sheetModels) {
        String wantedActive = normalizeId(activeSessionId);
        LinkedHashMap<String, SessionInfo> clean = new LinkedHashMap<>();
        if (sessions != null) {
            for (Map.Entry<String, SessionInfo> entry : sessions.entrySet()) {
                String key = normalizeId(entry.getKey());
                SessionInfo info = entry.getValue();
                if (key.isEmpty() || info == null) {
                    continue;
                }
                clean.put(key, new SessionInfo(key, info.name(), info.started(), info.masters(), info.members()));
            }
        }

        if (!clean.containsKey(wantedActive)) {
            wantedActive = "";
            for (Map.Entry<String, SessionInfo> entry : clean.entrySet()) {
                if (entry.getValue().started()) {
                    wantedActive = entry.getKey();
                    break;
                }
            }
        }

        boolean changed = !clean.equals(sessions) || !wantedActive.equals(normalizeId(activeSessionId));
        for (Map.Entry<String, SessionInfo> entry : clean.entrySet()) {
            boolean started = !wantedActive.isEmpty() && entry.getKey().equals(wantedActive) && entry.getValue().started();
            if (started != entry.getValue().started()) {
                entry.setValue(new SessionInfo(entry.getValue().id(), entry.getValue().name(), started,
                        entry.getValue().masters(), entry.getValue().members()));
                changed = true;
            }
        }

        this.sessions = clean;
        this.activeSessionId = wantedActive;
        this.threats = new LinkedHashMap<>();
        if (threats != null) {
            for (Map.Entry<String, List<ThreatSheet>> entry : threats.entrySet()) {
                String key = normalizeId(entry.getKey());
                if (key.isEmpty() || entry.getValue() == null) {
                    continue;
                }
                this.threats.put(key, Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
        }
        this.presets = new LinkedHashMap<>();
        if (presets != null) {
            for (Map.Entry<String, List<RollPreset>> entry : presets.entrySet()) {
                String key = normalizeId(entry.getKey());
                if (key.isEmpty() || entry.getValue() == null) {
                    continue;
                }
                this.presets.put(key, Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            }
        }
        this.diaries = new LinkedHashMap<>();
        if (diaries != null) {
            for (Map.Entry<String, DiaryStore.Diary> entry : diaries.entrySet()) {
                String key = normalizeId(entry.getKey());
                DiaryStore.Diary diary = entry.getValue();
                if (key.isEmpty() || diary == null) {
                    continue;
                }
                this.diaries.put(key, diary);
            }
        }
        this.sheetModels = new LinkedHashMap<>();
        if (sheetModels != null) {
            for (Map.Entry<String, SheetModel> entry : sheetModels.entrySet()) {
                String key = normalizeId(entry.getKey());
                SheetModel model = entry.getValue();
                if (key.isEmpty() || model == null) {
                    continue;
                }
                this.sheetModels.put(key, model);
            }
        }
        if (!this.sessions.containsKey(DEFAULT_ID)) {
            String name = cleanName(SessionManager.getSessionName(), DEFAULT_ID);
            this.sessions.put(DEFAULT_ID, new SessionInfo(DEFAULT_ID, name, false,
                    SessionManager.getMasterUuids(), List.of()));
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public static SessionStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            SessionStore store = get(server);
            TabletopRpg.LOGGER.info("[TabletopRPG] Sessions loaded: {} (active: {}).",
                    store.all().size(), store.activeId().isEmpty() ? "none" : store.activeId());
            store.clearActive();
            store.clearAllMembers();
            for (SessionInfo info : store.all()) {
                for (UUID id : info.masters()) {
                    store.releaseMaster(id);
                }
            }
            SessionManager.releaseMaster();
            SessionManager.setMode(SessionManager.GameMode.FREE);
            TurnManager.reset();
            ThreatSheetStore.clearAll();
            RollPresetStore.clearSessionCache();
            DiaryStore.clearSessionCache();
        });
    }

    public static void applyToServer(MinecraftServer server) {
        if (server == null) {
            return;
        }
        SessionStore store = get(server);
        SessionInfo active = store.active();
        SessionManager.syncMastersFromActiveSession(server, active != null ? active : store.get(DEFAULT_ID));
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.gameMode.getGameModeForPlayer() == GameType.SPECTATOR) {
                player.setGameMode(GameType.SURVIVAL);
            }
        }
        SheetModelHolder.set(store.effectiveSheetModel());
        RpgNetworking.sendToAll(server);
        RpgNetworking.broadcastSheetModel(server, SheetModelHolder.current());
    }

    public void clearAllMembers() {
        for (Map.Entry<String, SessionInfo> entry : sessions.entrySet()) {
            SessionInfo info = entry.getValue();
            entry.setValue(new SessionInfo(info.id(), info.name(), info.started(), info.masters(), List.of()));
        }
        setDirty();
    }

    private Map<String, SessionInfo> sessions() {
        return sessions;
    }

    public Map<String, List<ThreatSheet>> threats() {
        return Collections.unmodifiableMap(threats);
    }

    public List<ThreatSheet> threats(String sessionId) {
        List<ThreatSheet> sheets = threats.get(normalizeId(sessionId));
        return sheets == null ? List.of() : sheets;
    }

    public void replaceThreats(String sessionId, List<ThreatSheet> sheets) {
        String key = normalizeId(sessionId);
        if (key.isEmpty()) {
            return;
        }
        if (sheets == null || sheets.isEmpty()) {
            threats.remove(key);
        } else {
            threats.put(key, Collections.unmodifiableList(new ArrayList<>(sheets)));
        }
        setDirty();
    }

    private Map<String, List<RollPreset>> presets() {
        return Collections.unmodifiableMap(presets);
    }

    public List<RollPreset> presets(String sessionId) {
        List<RollPreset> list = presets.get(normalizeId(sessionId));
        return list == null ? List.of() : list;
    }

    public void replacePresets(String sessionId, List<RollPreset> presetList) {
        String key = normalizeId(sessionId);
        if (key.isEmpty()) {
            return;
        }
        if (presetList == null || presetList.isEmpty()) {
            presets.remove(key);
        } else {
            presets.put(key, Collections.unmodifiableList(new ArrayList<>(presetList)));
        }
        setDirty();
    }

    private Map<String, DiaryStore.Diary> diaries() {
        return Collections.unmodifiableMap(diaries);
    }

    public DiaryStore.Diary diary(String sessionId) {
        DiaryStore.Diary diary = diaries.get(normalizeId(sessionId));
        return diary == null ? DiaryStore.Diary.EMPTY : diary;
    }

    public void replaceDiary(String sessionId, DiaryStore.Diary diary) {
        String key = normalizeId(sessionId);
        if (key.isEmpty()) {
            return;
        }
        DiaryStore.Diary value = diary == null ? DiaryStore.Diary.EMPTY : diary;
        boolean hasState = !value.entries().isEmpty()
                || value.nextId() > DiaryStore.Diary.EMPTY.nextId()
                || value.pinCounter() > DiaryStore.Diary.EMPTY.pinCounter();
        if (!hasState) {
            diaries.remove(key);
        } else {
            diaries.put(key, new DiaryStore.Diary(
                    Collections.unmodifiableList(new ArrayList<>(value.entries())),
                    Math.max(1, value.nextId()),
                    Math.max(1L, value.pinCounter())));
        }
        setDirty();
    }

    private Map<String, SheetModel> sheetModels() {
        return Collections.unmodifiableMap(sheetModels);
    }

    public SheetModel sheetModel(String sessionId) {
        return sheetModels.get(normalizeId(sessionId));
    }

    public void replaceSheetModel(String sessionId, SheetModel model) {
        String key = normalizeId(sessionId);
        if (key.isEmpty()) {
            return;
        }
        if (model == null) {
            sheetModels.remove(key);
        } else {
            sheetModels.put(key, model);
        }
        setDirty();
    }

    public List<SessionInfo> all() {
        return List.copyOf(sessions.values());
    }

    public SessionInfo active() {
        return activeSessionId == null ? null : sessions.get(activeSessionId);
    }

    public String activeId() {
        return activeSessionId == null ? "" : activeSessionId;
    }

    public SessionInfo get(String id) {
        return sessions.get(normalizeId(id));
    }

    public SessionInfo create(String name, UUID creator) {
        String id = generateId();
        List<UUID> masters = creator == null ? List.of() : List.of(creator);
        SessionInfo info = new SessionInfo(id, name, false, masters, List.of());
        sessions.put(id, info);
        setDirty();
        return info;
    }

    public boolean setActive(String id) {
        String target = normalizeId(id);
        if (!sessions.containsKey(target)) {
            return false;
        }
        boolean changed = setStarted(target, true);
        if (!target.equals(activeSessionId)) {
            activeSessionId = target;
            changed = true;
        }
        if (changed) {
            setDirty();
        }
        ThreatSheetStore.loadSession(target, threats(target));
        RollPresetStore.loadSession(target, presets(target));
        DiaryStore.loadSession(target, diary(target));
        SheetModelHolder.set(effectiveSheetModel());
        SessionManager.realignAllSheets();
        return true;
    }

    public void clearActive() {
        boolean changed = setStarted("", false);
        if (!activeSessionId.isEmpty()) {
            activeSessionId = "";
            changed = true;
        }
        if (changed) {
            setDirty();
        }
        ThreatSheetStore.clearAll();
        RollPresetStore.clearSessionCache();
        DiaryStore.clearSessionCache();
        SheetModelHolder.set(effectiveSheetModel());
        SessionManager.realignAllSheets();
    }

    public SheetModel effectiveSheetModel() {
        SessionInfo active = active();
        if (active != null) {
            SheetModel sessionModel = sheetModels.get(active.id());
            if (sessionModel != null) {
                return sessionModel;
            }
        }
        return SheetModelStore.fallbackModel();
    }

    public boolean join(String id, UUID player) {
        if (player == null) {
            return false;
        }
        String target = normalizeId(id);
        SessionInfo info = sessions.get(target);
        if (info == null || info.members().contains(player)) {
            return false;
        }
        List<UUID> members = new ArrayList<>(info.members());
        members.add(player);
        sessions.put(target, new SessionInfo(info.id(), info.name(), info.started(), info.masters(), members));
        setDirty();
        return true;
    }

    public boolean joinExclusive(String id, UUID player) {
        leave(player);
        return join(id, player);
    }

    public boolean leave(UUID player) {
        if (player == null) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<String, SessionInfo> entry : new ArrayList<>(sessions.entrySet())) {
            SessionInfo info = entry.getValue();
            if (!info.members().contains(player)) {
                continue;
            }
            List<UUID> members = new ArrayList<>(info.members());
            members.remove(player);
            sessions.put(entry.getKey(), new SessionInfo(info.id(), info.name(), info.started(), info.masters(), members));
            changed = true;
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean claimMaster(String id, UUID player, int maxMasters) {
        if (player == null) {
            return false;
        }
        String target = normalizeId(id);
        SessionInfo info = sessions.get(target);
        if (info == null) {
            return false;
        }
        if (info.masters().contains(player)) {
            return true;
        }
        if (info.masters().size() >= Math.max(0, maxMasters)) {
            return false;
        }
        List<UUID> masters = new ArrayList<>(info.masters());
        masters.add(player);
        sessions.put(target, new SessionInfo(info.id(), info.name(), info.started(), masters, info.members()));
        setDirty();
        return true;
    }

    public boolean releaseMaster(UUID player) {
        if (player == null) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<String, SessionInfo> entry : new ArrayList<>(sessions.entrySet())) {
            SessionInfo info = entry.getValue();
            if (!info.masters().contains(player)) {
                continue;
            }
            List<UUID> masters = new ArrayList<>(info.masters());
            masters.remove(player);
            sessions.put(entry.getKey(), new SessionInfo(info.id(), info.name(), info.started(), masters, info.members()));
            changed = true;
        }
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean releaseMaster(String id, UUID player) {
        if (player == null) {
            return false;
        }
        String target = normalizeId(id);
        SessionInfo info = sessions.get(target);
        if (info == null || !info.masters().contains(player)) {
            return false;
        }
        List<UUID> masters = new ArrayList<>(info.masters());
        masters.remove(player);
        sessions.put(target, new SessionInfo(info.id(), info.name(), info.started(), masters, info.members()));
        setDirty();
        return true;
    }

    public boolean isMaster(UUID player) {
        if (player == null) {
            return false;
        }
        SessionInfo info = active();
        if (info == null) {
            info = sessions.get(DEFAULT_ID);
        }
        return info != null && info.masters().contains(player);
    }

    public boolean isMember(UUID player) {
        if (player == null) {
            return false;
        }
        SessionInfo info = active();
        if (info == null) {
            info = sessions.get(DEFAULT_ID);
        }
        return info != null && info.members().contains(player);
    }

    public boolean isActive() {
        return active() != null;
    }

    public boolean hasActive() {
        return activeSessionId != null && !activeSessionId.isEmpty();
    }

    private boolean setStarted(String id, boolean started) {
        boolean changed = false;
        LinkedHashMap<String, SessionInfo> updated = new LinkedHashMap<>();
        for (Map.Entry<String, SessionInfo> entry : sessions.entrySet()) {
            SessionInfo info = entry.getValue();
            boolean next = started && entry.getKey().equals(id);
            if (next != info.started()) {
                info = new SessionInfo(info.id(), info.name(), next, info.masters(), info.members());
                changed = true;
            }
            updated.put(entry.getKey(), info);
        }
        sessions = updated;
        return changed;
    }

    private String generateId() {
        while (true) {
            StringBuilder id = new StringBuilder(ID_LENGTH);
            for (int i = 0; i < ID_LENGTH; i++) {
                id.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
            }
            String candidate = id.toString();
            if (!sessions.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    private static String normalizeId(String id) {
        if (id == null) {
            return "";
        }
        String clean = id.trim().toUpperCase(Locale.ROOT);
        return clean.length() > 8 ? clean.substring(0, 8) : clean;
    }

    private static String cleanName(String name, String id) {
        String clean = name == null ? "" : name.trim();
        if (clean.length() > 64) {
            clean = clean.substring(0, 64);
        }
        return clean.isEmpty() ? "Session " + id : clean;
    }

    private static List<UUID> unique(List<UUID> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<UUID> clean = new LinkedHashSet<>();
        for (UUID value : values) {
            if (value != null) {
                clean.add(value);
            }
        }
        return List.copyOf(clean);
    }
}
