package damage_numbers;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Scl;
import arc.struct.IntSet;
import arc.struct.LongMap;
import arc.struct.Seq;
import arc.util.Align;
import arc.util.Log;
import arc.util.Time;
import arc.util.Tmp;
import arc.util.pooling.Pools;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.entities.Damage;
import mindustry.entities.bullet.BulletType;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.mod.Mod;
import mindustry.type.StatusEffect;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.CheckSetting;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.Setting;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.SliderSetting;

import java.util.concurrent.ConcurrentHashMap;

public class DamageNumbersMod extends Mod {

    public DamageNumbersMod() {
        Log.info("===== CONSTRUCTOR EJECUTADO =====");
    }

    private static final String MOD_NAME = "damage-numbers";

    // --- Dano anulado: cuando el dano verdadero es 0 ---
    private static final String NULLED_TEXT = "nulled";
    private static final String NULLED_SPRITE = "nullDamage"; // assets/sprites/nullDamage.png

    // --- Colores por defecto de los estados "solo efecto" (hex sin #) ---
    private static final String DEFAULT_WET_COLOR = "4169e1";
    private static final String DEFAULT_TARRED_COLOR = "6b6b6b"; // el real (313131) se pierde contra el contorno negro
    private static final String DEFAULT_FREEZING_COLOR = "6ecdec";

    // --- Ajustes del menu del juego (se guardan en Core.settings) ---
    private static final String KEY_TEXT_SIZE = "damage-numbers-text-size";
    private static final String KEY_ICON_SPACING = "damage-numbers-icon-spacing";
    private static final String KEY_ICON_DISPLAY = "damage-numbers-icon-display";
    private static final String KEY_DAMAGE_FUSION = "damage-numbers-damage-fusion";
    private static final String KEY_STATUS_FUSION = "damage-numbers-status-fusion";
    private static final String KEY_STATUS_FLOOD = "damage-numbers-status-flood";
    private static final String KEY_DAMAGE_TYPE = "damage-numbers-damage-type"; // "raw" | "true" | "both"
    private static final String KEY_ANIM_BOUNCE = "damage-numbers-anim-bounce";
    private static final String KEY_ANIM_FADE_IN = "damage-numbers-anim-fade-in";
    private static final String KEY_ANIM_FADE_OUT = "damage-numbers-anim-fade-out";
    private static final String KEY_ANIM_GROW = "damage-numbers-anim-grow";
    private static final String KEY_ANIM_SHRINK = "damage-numbers-anim-shrink";
    private static final String DAMAGE_TYPE_DEFAULT = "raw";

    private static final int TEXT_SIZE_DEFAULT = 20, TEXT_SIZE_MIN = 1, TEXT_SIZE_MAX = 50;
    private static final int ICON_SPACING_DEFAULT = 3, ICON_SPACING_MIN = 1, ICON_SPACING_MAX = 20;

    // Icono de la categoria en el menu: assets/sprites/damage-numbers-icon.png
    // (el juego le antepone el nombre del mod, por eso el nombre final repite "damage-numbers")
    private static final String CATEGORY_ICON_SPRITE = "damage-numbers-icon";

    // Iconos de los encabezados de widget (assets/sprites/<nombre>.png). Si falta uno,
    // el encabezado sale sin icono y el resto funciona igual.
    private static final String CONFIG_ICON_SPRITE = "configuration-icon";
    private static final String ANIMATION_ICON_SPRITE = "animation-effect-icon";
    private static final String DAMAGE_TYPE_ICON_SPRITE = "icon-kinetic";

    // --- Efectos de animacion (tiempos en segundos, escalas como multiplicador) ---
    private static final float FADE_IN_SECONDS = 0.2f;     // Fade in: casi instantaneo (se suma a la vida del texto)
    private static final float FADE_OUT_SECONDS = 1f;      // Fade out: se suma a la vida del texto
    private static final float BOUNCE_PERIOD = 0.6f;       // Bounce: duracion de cada pulso
    private static final float BOUNCE_MAX_SCALE = 1.4f;    // Bounce: 1.0 -> 1.4 -> 1.0
    private static final float GROW_START_SCALE = 0.3f;    // Grow: tamano con el que nace
    private static final float GROW_INTRO_SECONDS = 0.25f; // Grow: tiempo en llegar al tamano real
    private static final float GROW_END_SCALE = 1.5f;      // Grow: tamano que alcanza justo al desaparecer
    private static final float END_EFFECT_SECONDS = 0.5f;  // Grow/Shrink: ventana final de la vida del texto
    private static final float MIN_VISIBLE_SCALE = 0.02f;  // por debajo de esto no se dibuja

    // Tipos de objetivo/estado para las claves de fusion
    private static final int KIND_UNIT_DAMAGE = 1, KIND_BUILD_DAMAGE = 2, KIND_STATUS = 3;

    // --- Configuracion de config.hjson (lo que NO esta en el menu) ---
    private boolean showAllyUnitDamage = true;
    private boolean showEnemyUnitDamage = true;
    private boolean showAllyBuildingDamage = true;
    private boolean showEnemyBuildingDamage = true;
    private boolean showShieldNulled = true;
    private float labelLifetimeTicks = 40f;
    private float iconSize = 6f;
    private float holdSeconds = 0.15f;
    private float riseSpeed = 8f;

    // Colores de los textos de estado de proyectiles de liquido
    private Color wetColor = Color.valueOf(DEFAULT_WET_COLOR);
    private Color tarredColor = Color.valueOf(DEFAULT_TARRED_COLOR);
    private Color freezingColor = Color.valueOf(DEFAULT_FREEZING_COLOR);

    // --- Dano "true" (metodo hibrido) ---
    // Golpes directos -> formula real del juego.
    // Splash          -> diferencia de vida antes/despues del golpe.
    private final ConcurrentHashMap<Integer, Float> unitHealthMemory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Float> buildHealthMemory = new ConcurrentHashMap<>();

    private final ObjectMapCache spriteCache = new ObjectMapCache();

    // --- Un texto flotante con su icono, que comparten un unico ciclo de animacion ---
    private static class Popup {
        String text;                 // texto a mostrar (sin etiquetas de color)
        final Color color = new Color(1f, 1f, 1f, 1f); // color del texto
        float fontSize;              // tamano base del texto (ajuste "Text size")
        float textWidth, textHeight; // medidas del texto a escala 1, en unidades de mundo
        TextureRegion icon;          // null si no hay icono
        float anchorX, anchorY;      // punto central imaginario
        float textDx, iconDx;        // desplazamiento horizontal respecto al punto central
        float age;                   // segundos desde que (re)empezo la animacion
        float lifetime;              // duracion total de la animacion, en segundos

        // Efectos de animacion activos (se fijan cada vez que el popup se coloca/reinicia)
        boolean bounce, fadeIn, fadeOut, grow, shrink;

        // Datos de fusion
        float sumRaw, sumTrue;       // dano acumulado (fusion de dano)
        boolean hasKey;
        long fusionKey;
    }
    private final Seq<Popup> popups = new Seq<>();

    // Como se ve un popup en un instante dado (lo calcula computePose)
    private static class Pose {
        float dx, dy;                // desplazamiento respecto al punto central
        float scale = 1f;            // multiplicador de tamano (texto, icono y separacion)
        float alpha = 1f;            // opacidad, de 0 a 1
    }
    private final Pose pose = new Pose();
    private final Color drawColor = new Color();

    // Textos fusionables activos: clave = (objetivo, tipo, estado)
    private final LongMap<Popup> fusionIndex = new LongMap<>();

    // --- "Status flood": estados que ya anunciamos y que la unidad todavia conserva ---
    private static class StatusMark {
        Unit unit;
        int unitId;
        StatusEffect status;
        long key;
    }
    private final Seq<StatusMark> statusMarks = new Seq<>();
    private final LongMap<StatusMark> statusMarkIndex = new LongMap<>();

    // --- Seguimiento de proyectiles para detectar los absorbidos por escudos ---
    private final Seq<Bullet> prevBullets = new Seq<>();
    private final Seq<Bullet> curBullets = new Seq<>();
    private final IntSet curBulletIds = new IntSet();
    private final Cons<Bullet> bulletCollector = b -> {
        if (b.type != null && b.type.absorbable) {
            curBullets.add(b);
            curBulletIds.add(b.id);
        }
    };

    @Override
    public void init() {
        Log.info("==== INIT EJECUTANDO ====");

        loadConfig();

        // El menu se registra cuando el juego termino de cargar
        Events.on(EventType.ClientLoadEvent.class, e -> addSettingsMenu());

        Log.info("[Damage Numbers] Registrando eventos...");

        // Al cargar un mapa nuevo todas las entidades desaparecen: reiniciamos el estado
        Events.on(EventType.WorldLoadEvent.class, e -> {
            popups.clear();
            fusionIndex.clear();
            statusMarks.clear();
            statusMarkIndex.clear();
            prevBullets.clear();
            unitHealthMemory.clear();
            buildHealthMemory.clear();
        });

        Events.on(EventType.UnitDestroyEvent.class, e -> {
            if (e.unit != null) unitHealthMemory.remove(e.unit.id);
        });
        Events.on(EventType.BlockDestroyEvent.class, e -> {
            if (e.tile != null && e.tile.build != null) {
                buildHealthMemory.remove(e.tile.build.id);
            }
        });

        // --- Anima y dibuja los textos (y sus iconos) cada frame ---
        // Dibujamos nosotros el texto (ya no usamos WorldLabel) para controlar su
        // posicion, tamano y opacidad en cada cuadro.
        Events.run(EventType.Trigger.drawOver, () -> {
            if (popups.isEmpty()) return;

            // En pausa el tiempo no avanza: todo se queda congelado (sin flotar ni
            // desaparecer), pero se sigue dibujando para que no se esfume.
            float dt = Vars.state.isPaused() ? 0f : Time.delta / 60f;

            // Mismo estado de dibujo que usa el juego para sus textos de mundo: capa por
            // encima de unidades y efectos, y fuera del pixelado si esta activo.
            float oldZ = Draw.z();
            Draw.z(Layer.playerName + 1f);
            Drawf.text();

            Rect view = Core.camera.bounds(Tmp.r1);

            for (int i = popups.size - 1; i >= 0; i--) {
                Popup p = popups.get(i);

                p.age += dt;
                if (p.age >= p.lifetime) {
                    releasePopup(p);
                    popups.remove(i);
                    continue;
                }

                computePose(p, pose);

                // Totalmente transparente o demasiado pequeno: no hay nada que dibujar
                if (pose.alpha <= 0f || pose.scale < MIN_VISIBLE_SCALE) continue;

                float x = p.anchorX + pose.dx;
                float y = p.anchorY + pose.dy;

                // Fuera de la camara no dibujamos nada
                float reach = (Math.abs(p.iconDx) + p.textDx + p.textWidth) * pose.scale + iconSize;
                if (!view.overlaps(x - reach, y - reach, reach * 2f, reach * 2f)) continue;

                if (p.icon != null) {
                    // Reset ANTES de dibujar: limpia cualquier "mixcol" (tinte de
                    // destello) que haya dejado un efecto anterior en este frame.
                    Draw.reset();
                    Draw.color(Color.white, pose.alpha);
                    float size = iconSize * pose.scale;
                    Draw.rect(p.icon, p.anchorX + pose.dx + p.iconDx * pose.scale, y, size, size);
                    Draw.reset();
                }

                drawText(p, p.anchorX + pose.dx + p.textDx * pose.scale, y, pose.scale, pose.alpha);
            }

            Draw.reset();
            Draw.z(oldZ);
        });

        // --- Proyectiles absorbidos por escudos (ForceProjector, ForceFieldAbility, etc.) ---
        // No existe un evento de "proyectil absorbido". Cada tick, justo despues de
        // actualizar las entidades, comparamos los proyectiles del tick anterior con
        // los actuales: los que desaparecieron y quedaron marcados como "absorbed"
        // fueron detenidos por un escudo. El juego libera (y resetea) los objetos
        // eliminados recien al inicio del SIGUIENTE tick, asi que aqui aun son legibles.
        Events.run(EventType.Trigger.afterGameUpdate, () -> {
            cleanStatusMarks();

            if (!showShieldNulled) return;

            curBullets.clear();
            curBulletIds.clear();
            Groups.bullet.each(bulletCollector);

            for (int i = 0; i < prevBullets.size; i++) {
                Bullet b = prevBullets.get(i);
                if (b.absorbed && !curBulletIds.contains(b.id)) onBulletAbsorbed(b);
            }

            prevBullets.clear();
            prevBullets.addAll(curBullets);
        });

        // --- Dano a unidades ---
        Events.on(EventType.UnitDamageEvent.class, e -> {
            if (e.bullet == null || e.unit == null) return;
            BulletType type = e.bullet.type;
            float rawDamage = e.bullet.damage;

            // Proyectiles de liquido (mojado/alquitranado/congelante) sin dano real:
            // lo que importa es el estado, no el numero.
            boolean statusOnly = isStatusOnlyType(type) && Math.round(rawDamage) <= 0;
            if (rawDamage <= 0 && !statusOnly) return;

            Unit unit = e.unit;
            int id = unit.id;
            boolean isSplash = type.splashDamage > 0;

            // Siempre actualizamos la memoria de vida (la usa el splash).
            Float prevHealthObj = unitHealthMemory.get(id);
            float prevHealth = (prevHealthObj != null) ? prevHealthObj : unit.health + rawDamage;
            float diffDamage = Math.max(prevHealth - unit.health, 0);
            unitHealthMemory.put(id, unit.health);

            float trueDamage = isSplash ? diffDamage : computeFormulaTrueDamageUnit(unit, e.bullet);

            Team playerTeam = Vars.player != null ? Vars.player.team() : null;
            boolean isAlly = playerTeam != null && playerTeam == unit.team;

            if (isAlly && !showAllyUnitDamage) return;
            if (!isAlly && !showEnemyUnitDamage) return;

            float x = unit.x, y = unit.y + unit.hitSize / 2f;

            if (statusOnly) {
                showStatus(unit, type.status, x, y);
            } else {
                showDamage(unit.id, KIND_UNIT_DAMAGE, rawDamage, trueDamage, isAlly, type, x, y);
            }
        });

        // --- Dano a edificios ---
        // (Las estructuras no reciben estados alterados, asi que aqui no hay modo "solo estado".)
        Events.on(EventType.BuildDamageEvent.class, e -> {
            if (e.source == null || e.build == null) return;
            float rawDamage = e.source.damage;
            if (rawDamage <= 0) return;

            Building build = e.build;
            int id = build.id;
            boolean isSplash = e.source.type.splashDamage > 0;

            Float prevHealthObj = buildHealthMemory.get(id);
            float prevHealth = (prevHealthObj != null) ? prevHealthObj : build.health + rawDamage;
            float diffDamage = Math.max(prevHealth - build.health, 0);
            buildHealthMemory.put(id, build.health);

            float trueDamage = isSplash ? diffDamage : computeFormulaTrueDamageBuilding(build, e.source);

            Team playerTeam = Vars.player != null ? Vars.player.team() : null;
            boolean isAlly = playerTeam != null && playerTeam == build.team;

            if (isAlly && !showAllyBuildingDamage) return;
            if (!isAlly && !showEnemyBuildingDamage) return;

            showDamage(build.id, KIND_BUILD_DAMAGE, rawDamage, trueDamage, isAlly, e.source.type, build.x, build.y);
        });

        Log.info("[Damage Numbers] Mod cargado correctamente!");
    }

    // =====================================================================
    //  Menu de ajustes (en ingles)
    // =====================================================================

    private void addSettingsMenu() {
        try {
            // Si todavia no existe damage-numbers-icon.png, la categoria sale sin icono.
            TextureRegion iconRegion = getCustomSprite(CATEGORY_ICON_SPRITE);
            Drawable categoryIcon = iconRegion == null ? null : new TextureRegionDrawable(iconRegion);

            Vars.ui.settings.addCategory("Damage Numbers", categoryIcon, table -> {
                // Widget "Damage type" (lleva su propio encabezado)
                table.pref(damageTypeSetting());

                // Widget "Configuration": los ajustes generales
                table.pref(headerSetting("Configuration", CONFIG_ICON_SPRITE));
                table.pref(slider(KEY_TEXT_SIZE, TEXT_SIZE_DEFAULT, TEXT_SIZE_MIN, TEXT_SIZE_MAX,
                        "Text size", "Size of the floating damage text."));
                table.pref(slider(KEY_ICON_SPACING, ICON_SPACING_DEFAULT, ICON_SPACING_MIN, ICON_SPACING_MAX,
                        "Icon spacing", "Increases or decreases the space between the text and the damage type icon."));
                table.pref(check(KEY_ICON_DISPLAY, true,
                        "Icon display", "Show the damage type icon next to the text."));
                table.pref(check(KEY_DAMAGE_FUSION, false,
                        "Damage text fusion", "Continuous damage on the same target is added into a single text, and its animation restarts with every new hit."));
                table.pref(check(KEY_STATUS_FUSION, true,
                        "Status text fusion", "A status text (wet, freezing, tarred) plays its animation once per target and only appears again after the animation ends."));
                table.pref(check(KEY_STATUS_FLOOD, true,
                        "Status flood", "When a unit is hit by a status effect, its status text will not appear again until the status ends and is applied again."));

                // Widget "Animation effect": efectos acumulables
                table.pref(headerSetting("Animation effect", ANIMATION_ICON_SPRITE));
                table.pref(check(KEY_ANIM_BOUNCE, false,
                        "Bounce", "The text pulses in size (1.0 > 1.4 > 1.0) in a loop until it disappears."));
                table.pref(check(KEY_ANIM_FADE_IN, false,
                        "Fade in", "The text appears almost instantly from transparent. Adds a short time to its lifetime."));
                table.pref(check(KEY_ANIM_FADE_OUT, false,
                        "Fade out", "The text fades out at the end of its life. Adds 1 second to its lifetime."));
                table.pref(check(KEY_ANIM_GROW, false,
                        "Grow", "The text starts small, grows to its real size, and keeps growing near the end until it disappears."));
                table.pref(check(KEY_ANIM_SHRINK, false,
                        "Shrink", "The text shrinks near the end until it disappears. Together with Grow, it starts small, reaches its real size and shrinks at the end."));
            });
        } catch (Exception ex) {
            Log.err("[Damage Numbers] No se pudo crear el menu de ajustes: " + ex.getMessage());
        }
    }

    // Tipo de dano que se muestra: "raw" (crudo), "true" (verdadero) o "both" (ambos).
    // Se lee en cada golpe, asi el cambio del menu aplica al instante.
    private String damageMode() {
        String v = Core.settings.getString(KEY_DAMAGE_TYPE, DAMAGE_TYPE_DEFAULT);
        return (v.equals("raw") || v.equals("true") || v.equals("both")) ? v : DAMAGE_TYPE_DEFAULT;
    }

    // Encabezado de un widget: icono a la izquierda y titulo en color de acento.
    private void addHeader(SettingsTable table, String title, String iconSprite) {
        float width = Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f));

        table.table(header -> {
            header.left();
            TextureRegion icon = getCustomSprite(iconSprite);
            if (icon != null) header.image(icon).size(28f).padRight(8f);
            header.add(title).color(Pal.accent);
        }).width(width).left().padTop(10f).row();
    }

    // Un "ajuste" que solo dibuja el encabezado de un widget; los ajustes que se
    // agregan justo despues quedan agrupados debajo de el.
    // OJO: los parametros NO se llaman "title": dentro de la clase anonima esa palabra
    // apuntaria al campo "title" que hereda Setting (su nombre interno), no al parametro.
    private Setting headerSetting(String headerText, String headerIconSprite) {
        return new Setting("damage-numbers-header-" + headerText.toLowerCase().replace(' ', '-')) {
            @Override
            public void add(SettingsTable table) {
                addHeader(table, headerText, headerIconSprite);
            }
        };
    }

    // Selector de tipo de dano: titulo con icono, fila con botones Raw / True / Both y descripcion.
    private Setting damageTypeSetting() {
        Core.settings.defaults(KEY_DAMAGE_TYPE, DAMAGE_TYPE_DEFAULT);

        return new Setting(KEY_DAMAGE_TYPE) {
            @Override
            public void add(SettingsTable table) {
                float width = Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f));

                // Titulo con el icono de dano cinetico (assets/sprites/icon-kinetic.png)
                addHeader(table, "Damage type", DAMAGE_TYPE_ICON_SPRITE);

                // Fila: nombre de la opcion + botones de seleccion
                table.table(row -> {
                    row.left();
                    row.add("Damage type").left().growX();

                    ButtonGroup<TextButton> group = new ButtonGroup<>();
                    String[][] options = {{"Raw", "raw"}, {"True", "true"}, {"Both", "both"}};
                    for (String[] option : options) {
                        String value = option[1];
                        row.button(option[0], Styles.flatTogglet, () -> Core.settings.put(KEY_DAMAGE_TYPE, value))
                                .group(group)
                                .checked(b -> damageMode().equals(value))
                                .size(80f, 40f).padLeft(4f);
                    }
                }).width(width).left().padTop(4f).row();

                // Descripcion
                table.add("Raw: the projectile's base damage. True: the damage actually dealt after armor and shields. Both: shows the two values, like 50(30).")
                        .wrap().width(width).left().color(Color.lightGray).padTop(4f).padBottom(6f).row();
            }
        };
    }

    private SliderSetting slider(String key, int def, int min, int max, String title, String description) {
        Core.settings.defaults(key, def);
        SliderSetting s = new SliderSetting(key, def, min, max, 1, i -> String.valueOf(i), null);
        s.title = title;
        s.description = description;
        return s;
    }

    private CheckSetting check(String key, boolean def, String title, String description) {
        Core.settings.defaults(key, def);
        CheckSetting s = new CheckSetting(key, def, null);
        s.title = title;
        s.description = description;
        return s;
    }

    // Los valores se leen en cada golpe, asi los cambios del menu aplican al instante.
    private float fontSize() {
        int v = Mathf.clamp(Core.settings.getInt(KEY_TEXT_SIZE, TEXT_SIZE_DEFAULT), TEXT_SIZE_MIN, TEXT_SIZE_MAX);
        return v / 10f; // 20 = tamano 2.0
    }

    // "Icon spacing" es la separacion TOTAL entre icono y texto; cada lado recibe la mitad.
    private float halfSpacing() {
        int v = Mathf.clamp(Core.settings.getInt(KEY_ICON_SPACING, ICON_SPACING_DEFAULT), ICON_SPACING_MIN, ICON_SPACING_MAX);
        return v / 2f;
    }

    private boolean iconDisplay() {
        return Core.settings.getBool(KEY_ICON_DISPLAY, true);
    }

    private boolean damageFusion() {
        return Core.settings.getBool(KEY_DAMAGE_FUSION, false);
    }

    private boolean statusFusion() {
        return Core.settings.getBool(KEY_STATUS_FUSION, true);
    }

    private boolean statusFlood() {
        return Core.settings.getBool(KEY_STATUS_FLOOD, true);
    }

    private boolean animBounce() {
        return Core.settings.getBool(KEY_ANIM_BOUNCE, false);
    }

    private boolean animFadeIn() {
        return Core.settings.getBool(KEY_ANIM_FADE_IN, false);
    }

    private boolean animFadeOut() {
        return Core.settings.getBool(KEY_ANIM_FADE_OUT, false);
    }

    private boolean animGrow() {
        return Core.settings.getBool(KEY_ANIM_GROW, false);
    }

    private boolean animShrink() {
        return Core.settings.getBool(KEY_ANIM_SHRINK, false);
    }

    // =====================================================================
    //  Mostrar textos (con fusion opcional)
    // =====================================================================

    private static long fusionKey(int targetId, int kind, int statusId) {
        return ((long) targetId << 24) | ((long) kind << 16) | (statusId & 0xFFFF);
    }

    // Dano numerico. Con "Damage text fusion" el dano continuo sobre el mismo objetivo
    // se suma en un solo texto y la animacion se reinicia en cada golpe nuevo.
    // Un golpe anulado ("nulled") nunca se fusiona.
    private void showDamage(int targetId, int kind, float raw, float trueDamage, boolean isAlly,
                            BulletType type, float x, float y) {
        boolean nulledHit = !damageMode().equals("raw") && isNulled(trueDamage);

        if (damageFusion() && !nulledHit) {
            long key = fusionKey(targetId, kind, 0);
            Popup existing = fusionIndex.get(key);

            if (existing != null) {
                existing.sumRaw += raw;
                existing.sumTrue += trueDamage;
                String text = formatDamageText(existing.sumRaw, existing.sumTrue);
                layoutPopup(existing, text, teamColor(isAlly), pickIcon(type, existing.sumTrue), x, y);
                return;
            }

            Popup created = spawnPair(formatDamageText(raw, trueDamage), teamColor(isAlly),
                    pickIcon(type, trueDamage), x, y);
            created.sumRaw = raw;
            created.sumTrue = trueDamage;
            created.hasKey = true;
            created.fusionKey = key;
            fusionIndex.put(key, created);
            return;
        }

        spawnPair(formatDamageText(raw, trueDamage), teamColor(isAlly), pickIcon(type, trueDamage), x, y);
    }

    // Texto de estado (mojado/alquitranado/congelante).
    //  - "Status flood": si ya anunciamos este estado a esta unidad y todavia lo tiene,
    //    no se vuelve a mostrar hasta que se le quite y se le aplique de nuevo.
    //  - "Status text fusion": mientras el texto del mismo estado siga animandose sobre la
    //    unidad, los golpes nuevos no crean otro; vuelve a aparecer cuando termina.
    private void showStatus(Unit unit, StatusEffect status, float x, float y) {
        boolean flood = statusFlood();
        long key = fusionKey(unit.id, KIND_STATUS, status.id);

        if (flood) {
            // Si es inmune, el estado nunca se aplica: no hay nada que anunciar.
            if (unit.isImmune(status)) return;
            if (statusMarkIndex.containsKey(key)) return;
        }

        boolean fuse = statusFusion();
        if (fuse && fusionIndex.containsKey(key)) return;

        Popup created = spawnPair(status.localizedName, statusColor(status), status.uiIcon, x, y);
        if (fuse) {
            created.hasKey = true;
            created.fusionKey = key;
            fusionIndex.put(key, created);
        }

        if (flood) {
            StatusMark mark = new StatusMark();
            mark.unit = unit;
            mark.unitId = unit.id;
            mark.status = status;
            mark.key = key;
            statusMarks.add(mark);
            statusMarkIndex.put(key, mark);
        }
    }

    // Olvida los estados que la unidad ya perdio (o la unidad que ya no existe),
    // para poder anunciarlos de nuevo si se vuelven a aplicar.
    private void cleanStatusMarks() {
        for (int i = statusMarks.size - 1; i >= 0; i--) {
            StatusMark m = statusMarks.get(i);
            boolean stale = m.unit.id != m.unitId || m.unit.dead || !m.unit.isAdded()
                    || !m.unit.hasEffect(m.status);
            if (stale) {
                statusMarkIndex.remove(m.key);
                statusMarks.remove(i);
            }
        }
    }

    // Un proyectil fue absorbido por un escudo: mostramos "nulled" donde se detuvo
    private void onBulletAbsorbed(Bullet b) {
        Team playerTeam = Vars.player != null ? Vars.player.team() : null;
        // El escudo pertenece al equipo contrario al del proyectil. Si el proyectil
        // no es nuestro, el escudo que lo detuvo es nuestro (aliado).
        boolean isAlly = playerTeam != null && b.team != playerTeam;

        spawnPair(NULLED_TEXT, teamColor(isAlly), getCustomSprite(NULLED_SPRITE), b.x, b.y);
    }

    // =====================================================================
    //  Popups: texto + icono alrededor de un punto central imaginario
    // =====================================================================

    // Crea un texto nuevo con su icono. Icono a la izquierda del punto central, texto
    // a la derecha, con la misma separacion sin importar cuantos caracteres tenga.
    private Popup spawnPair(String text, Color color, TextureRegion icon, float anchorX, float anchorY) {
        Popup p = new Popup();
        layoutPopup(p, text, color, icon, anchorX, anchorY);
        popups.add(p);
        return p;
    }

    // Coloca (o recoloca) un popup y reinicia su animacion.
    private void layoutPopup(Popup p, String text, Color color, TextureRegion icon, float anchorX, float anchorY) {
        p.text = text;
        p.color.set(color);
        p.fontSize = fontSize();
        p.icon = iconDisplay() ? icon : null;
        p.anchorX = anchorX;
        p.anchorY = anchorY;
        p.age = 0f;

        p.bounce = animBounce();
        p.fadeIn = animFadeIn();
        p.fadeOut = animFadeOut();
        p.grow = animGrow();
        p.shrink = animShrink();

        // Fade in y Fade out suman su propia duracion: el texto aparece y desaparece
        // sin perder tiempo visible.
        p.lifetime = labelLifetimeTicks
                + (p.fadeIn ? FADE_IN_SECONDS : 0f)
                + (p.fadeOut ? FADE_OUT_SECONDS : 0f);

        measureText(p);

        if (p.icon != null) {
            float gap = halfSpacing();
            p.textDx = gap + p.textWidth / 2f;
            p.iconDx = -gap - iconSize / 2f;
        } else {
            p.textDx = 0f;
            p.iconDx = 0f;
        }
    }

    // Termina un popup: lo saca del indice de fusion.
    private void releasePopup(Popup p) {
        if (p.hasKey && fusionIndex.get(p.fusionKey) == p) fusionIndex.remove(p.fusionKey);
    }

    // Calcula como se ve el popup segun su edad: desplazamiento, tamano y opacidad.
    // Por ahora solo existe el desplazamiento "Standard" (quieto y luego sube); en las
    // siguientes etapas aqui se componen los desplazamientos alternativos.
    // Los efectos de animacion se pueden combinar entre si.
    private void computePose(Popup p, Pose out) {
        out.dx = 0f;
        out.dy = Math.max(0f, p.age - holdSeconds) * riseSpeed;

        float left = p.lifetime - p.age; // tiempo que le queda de vida

        // --- Opacidad: Fade in (al inicio) y Fade out (al final) ---
        float alpha = 1f;
        if (p.fadeIn) alpha = Math.min(alpha, Mathf.clamp(p.age / FADE_IN_SECONDS, 0f, 1f));
        if (p.fadeOut) alpha = Math.min(alpha, Mathf.clamp(left / FADE_OUT_SECONDS, 0f, 1f));
        out.alpha = alpha;

        // --- Tamano: Grow / Shrink (inicio y final de la vida) y Bounce (en bucle) ---
        float scale = 1f;
        float introWindow = Math.min(GROW_INTRO_SECONDS, p.lifetime / 2f);
        float endWindow = Math.min(END_EFFECT_SECONDS, p.lifetime / 2f);

        // Inicio: con Grow nace pequeno y llega al tamano real
        if (p.grow && p.age < introWindow) {
            scale = Mathf.lerp(GROW_START_SCALE, 1f, Mathf.clamp(p.age / introWindow, 0f, 1f));
        }

        // Final: Shrink se encoge hasta 0; Grow (sin Shrink) sigue agrandandose
        if (left < endWindow) {
            float t = Mathf.clamp(1f - left / endWindow, 0f, 1f);
            if (p.shrink) scale *= Mathf.lerp(1f, 0f, t);
            else if (p.grow) scale *= Mathf.lerp(1f, GROW_END_SCALE, t);
        }

        // Bounce: 1.0 -> 1.4 -> 1.0 en bucle, multiplica al resto
        if (p.bounce) {
            float pulse = 0.5f - 0.5f * Mathf.cos(p.age / BOUNCE_PERIOD * Mathf.PI2);
            scale *= 1f + (BOUNCE_MAX_SCALE - 1f) * pulse;
        }

        out.scale = Math.max(scale, 0f);
    }

    // Dibuja el texto centrado en (cx, cy), igual que WorldLabelComp.drawAt: misma
    // fuente con contorno, misma escala y misma alineacion; la opacidad va en el color.
    private void drawText(Popup p, float cx, float cy, float scale, float alpha) {
        Font font = Fonts.outline;

        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.25f * p.fontSize * scale / Scl.scl(1f));

        drawColor.set(p.color);
        drawColor.a *= alpha;
        font.setColor(drawColor);

        // En drawAt, "y" es la parte de arriba del texto: se sube la mitad de su alto
        font.draw(p.text, cx, cy + p.textHeight * scale / 2f, 0f, Align.center, false);

        font.getData().setScale(1f);
        font.setColor(Color.white);
        font.setUseIntegerPositions(ints);
    }

    // Mide el texto a escala 1 (misma fuente y misma escala que usa el juego).
    private void measureText(Popup p) {
        Font font = Fonts.outline;
        GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);

        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.25f * p.fontSize / Scl.scl(1f));
        layout.setText(font, p.text);
        p.textWidth = layout.width;
        p.textHeight = layout.height;

        Pools.free(layout);
        font.getData().setScale(1f);
        font.setUseIntegerPositions(ints);
    }

    // =====================================================================
    //  Calculo de dano verdadero
    // =====================================================================

    // --- Formula real de dano "true" para unidades (ShieldComp.damage/damageArmorMult) ---
    private float computeFormulaTrueDamageUnit(Unit unit, Bullet bullet) {
        BulletType type = bullet.type;

        if (!unit.type.killable) return 0f;

        float afterArmor;
        if (type.pierceArmor) {
            afterArmor = bullet.damage;
        } else {
            float effectiveArmor = unit.armorOverride >= 0 ? unit.armorOverride : unit.armor;
            if (type.armorMultiplier != 1) effectiveArmor *= type.armorMultiplier;
            afterArmor = Damage.applyArmor(bullet.damage, effectiveArmor);
        }

        afterArmor = afterArmor / unit.healthMultiplier / Vars.state.rules.unitHealth(unit.team);

        float shieldAbsorbed = Math.min(Math.max(unit.shield, 0), afterArmor);
        return Math.max(afterArmor - shieldAbsorbed, 0);
    }

    // --- Formula real de dano "true" para estructuras (BuildingComp.collision) ---
    private float computeFormulaTrueDamageBuilding(Building build, Bullet bullet) {
        BulletType type = bullet.type;
        float rawForBuilding = bullet.damage * bullet.buildingDamageMultiplier;

        if (type.pierceArmor) return rawForBuilding;

        float effectiveArmor = build.block.armor * type.armorMultiplier * type.blockArmorMultiplier;
        return Math.max(Damage.applyArmor(rawForBuilding, effectiveArmor), 0);
    }

    // =====================================================================
    //  Estados, iconos y formato de texto
    // =====================================================================

    // --- Estados "solo efecto" (proyectiles de liquido) ---
    private boolean isStatusOnlyType(BulletType type) {
        return type != null && (type.status == StatusEffects.wet
                || type.status == StatusEffects.tarred
                || type.status == StatusEffects.freezing);
    }

    // Color configurado para el texto de cada estado
    private Color statusColor(StatusEffect status) {
        return status == StatusEffects.wet ? wetColor
                : status == StatusEffects.tarred ? tarredColor
                : freezingColor;
    }

    // Convierte un hex ("4169e1" o "#4169e1") en un color; si es invalido usa el de respaldo
    private Color parseColor(String hex, String fallbackHex) {
        try {
            return Color.valueOf(hex);
        } catch (Exception ex) {
            Log.warn("[Damage Numbers] Color invalido '" + hex + "', usando " + fallbackHex);
            return Color.valueOf(fallbackHex);
        }
    }

    // --- Clasifica el tipo de dano y devuelve el icono a dibujar (o null si no hay) ---
    private TextureRegion iconFor(BulletType type) {
        if (type == null) return null;

        boolean isElectric = type.status == StatusEffects.shocked
                || type.status == StatusEffects.electrified
                || type.lightningDamage >= 0;
        if (isElectric) return StatusEffects.shocked.uiIcon;

        if (type.status == StatusEffects.freezing) return StatusEffects.freezing.uiIcon;

        boolean isFire = type.status == StatusEffects.burning || type.makeFire;
        if (isFire) return StatusEffects.burning.uiIcon;

        if (type.status == StatusEffects.blasted) return StatusEffects.blasted.uiIcon;
        if (type.status == StatusEffects.corroded) return StatusEffects.corroded.uiIcon;
        if (type.status == StatusEffects.melting) return StatusEffects.melting.uiIcon;
        if (type.status == StatusEffects.wet) return StatusEffects.wet.uiIcon;

        if (type.splashDamage > 0) return getCustomSprite("icon-splash");

        return getCustomSprite("icon-kinetic");
    }

    // El dano verdadero cuenta como "anulado" si se muestra como 0 (ya redondeado).
    private boolean isNulled(float trueDamage) {
        return Math.round(trueDamage) <= 0;
    }

    // En modo "raw" se muestra el dano crudo, asi que ahi nunca aplica "nulled".
    private TextureRegion pickIcon(BulletType type, float trueDamage) {
        if (!damageMode().equals("raw") && isNulled(trueDamage)) {
            TextureRegion nullIcon = getCustomSprite(NULLED_SPRITE);
            if (nullIcon != null) return nullIcon;
            // Si todavia no agregaste nullDamage.png, usamos el icono normal.
        }
        return iconFor(type);
    }

    private TextureRegion getCustomSprite(String name) {
        String fullName = MOD_NAME + "-" + name;
        return spriteCache.get(fullName, () -> {
            if (!Core.atlas.has(fullName)) {
                Log.warn("[Damage Numbers] No se encontro el sprite '" + fullName
                        + "'. Coloca el archivo en assets/sprites/" + name + ".png");
                return null;
            }
            return Core.atlas.find(fullName);
        });
    }

    private Color teamColor(boolean isAlly) {
        return isAlly ? Color.scarlet : Color.white;
    }

    private String formatDamageText(float raw, float trueDamage) {
        String rawStr = String.valueOf(Math.round(raw));
        String trueStr = isNulled(trueDamage) ? NULLED_TEXT : String.valueOf(Math.round(trueDamage));
        switch (damageMode()) {
            case "raw": return rawStr;
            case "true": return trueStr;
            default: return rawStr + "(" + trueStr + ")";
        }
    }

    // =====================================================================
    //  config.hjson
    // =====================================================================

    private void loadConfig() {
        try {
            var mod = Vars.mods.getMod(MOD_NAME);
            if (mod == null) {
                Log.warn("[Damage Numbers] Mod no encontrado, usando valores por defecto");
                return;
            }

            var configFile = mod.root.child("config.hjson");
            if (!configFile.exists()) {
                Log.warn("[Damage Numbers] config.hjson no encontrado, usando valores por defecto");
                return;
            }

            Jval json = Jval.read(configFile.readString());

            showAllyUnitDamage = json.getBool("showAllyUnitDamage", showAllyUnitDamage);
            showEnemyUnitDamage = json.getBool("showEnemyUnitDamage", showEnemyUnitDamage);
            showAllyBuildingDamage = json.getBool("showAllyBuildingDamage", showAllyBuildingDamage);
            showEnemyBuildingDamage = json.getBool("showEnemyBuildingDamage", showEnemyBuildingDamage);
            showShieldNulled = json.getBool("showShieldNulled", showShieldNulled);
            labelLifetimeTicks = json.getFloat("labelLifetimeTicks", labelLifetimeTicks);
            iconSize = json.getFloat("iconSize", iconSize);
            holdSeconds = json.getFloat("holdSeconds", holdSeconds);
            riseSpeed = json.getFloat("riseSpeed", riseSpeed);

            wetColor = parseColor(json.getString("wetColor", DEFAULT_WET_COLOR), DEFAULT_WET_COLOR);
            tarredColor = parseColor(json.getString("tarredColor", DEFAULT_TARRED_COLOR), DEFAULT_TARRED_COLOR);
            freezingColor = parseColor(json.getString("freezingColor", DEFAULT_FREEZING_COLOR), DEFAULT_FREEZING_COLOR);

            Log.info("[Damage Numbers] Configuracion cargada correctamente");
        } catch (Exception e) {
            Log.err("[Damage Numbers] Error al cargar config.hjson: " + e.getMessage());
            Log.err("[Damage Numbers] Usando valores por defecto");
        }
    }

    private static class ObjectMapCache {
        private final ConcurrentHashMap<String, TextureRegion> cache = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Boolean> resolved = new ConcurrentHashMap<>();

        TextureRegion get(String key, java.util.function.Supplier<TextureRegion> loader) {
            if (resolved.containsKey(key)) return cache.get(key);
            TextureRegion region = loader.get();
            if (region != null) cache.put(key, region);
            resolved.put(key, true);
            return region;
        }
    }
}
