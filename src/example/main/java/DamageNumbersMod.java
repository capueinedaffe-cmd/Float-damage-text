package damage_numbers;

import arc.Core;
import arc.Events;
import arc.func.Cons;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.Label;
import arc.scene.ui.TextButton;
import arc.scene.ui.TextField;
import arc.scene.ui.TextField.TextFieldFilter;
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
import mindustry.content.Fx;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.content.StatusEffects;
import mindustry.entities.Damage;
import mindustry.entities.bullet.BulletType;
import mindustry.entities.bullet.LiquidBulletType;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Bullet;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.MultiPacker;
import mindustry.graphics.Pal;
import mindustry.mod.Mod;
import mindustry.type.Category;
import mindustry.type.ItemStack;
import mindustry.type.Liquid;
import mindustry.type.StatusEffect;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.CheckSetting;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.Setting;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.SliderSetting;
import mindustry.world.blocks.defense.turrets.LiquidTurret;
import mindustry.world.draw.DrawTurret;
import mindustry.world.meta.BuildVisibility;

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
    private static final String KEY_DISPLACEMENT = "damage-numbers-displacement"; // "standard" | "ejection" | "wave" | "scatter"
    private static final String DISPLACEMENT_DEFAULT = "standard";
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
    private static final String DISPLACEMENT_ICON_SPRITE = "displacement-effect-icon";
    private static final String DAMAGE_RENDER_ICON_SPRITE = "damage-render-icon";
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

    // --- Desplazamientos (distancias en unidades de mundo, tiempos en segundos) ---
    private static final int DISP_STANDARD = 0, DISP_EJECTION = 1, DISP_WAVE = 2, DISP_SCATTER = 3;
    private static final float EJECT_APEX_TIME = 0.35f;    // Ejected casing: el punto mas alto, como fraccion de la vida
    private static final float EJECT_APEX_HEIGHT = 14f;    // Ejected casing: altura maxima sobre el punto de origen
    private static final float EJECT_DISTANCE = 24f;       // Ejected casing: distancia total hacia la derecha
    private static final float WAVE_AMPLITUDE = 6f;        // Continuous wave: cuanto se aleja del centro a cada lado (curva suave)
    private static final float WAVE_PERIOD = 0.8f;         // Continuous wave: duracion de un zigzag completo
    private static final float SCATTER_DISTANCE = 30f;     // Random scatter: distancia a la que se queda
    private static final float SCATTER_SECONDS = 0.35f;    // Random scatter: tiempo en llegar a ese punto

    // --- Efectos criticos (menu "Damage render") ---
    private static final String KEY_CRIT_ENABLED = "damage-numbers-crit-enabled";
    private static final String KEY_CRIT_FUSED = "damage-numbers-crit-threshold-fused";
    private static final String KEY_CRIT_SINGLE = "damage-numbers-crit-threshold-single";
    private static final String KEY_PARTICLE_LIMIT = "damage-numbers-particle-limit"; // "performance" | "regular" | "demanding" | "extreme"
    private static final String KEY_PARTICLE_SCALE = "damage-numbers-particle-scale";
    private static final String PARTICLE_LIMIT_DEFAULT = "regular";
    private static final int CRIT_FUSED_DEFAULT = 5000, CRIT_SINGLE_DEFAULT = 500, CRIT_MAX = 999999;
    private static final int PARTICLE_SCALE_DEFAULT = 100, PARTICLE_SCALE_MIN = 25, PARTICLE_SCALE_MAX = 300;

    // --- Particulas y efectos criticos: todo se dibuja por codigo (sin imagenes) ---
    private static final float PARTICLE_BASE_SIZE = 10f;   // tamano de referencia de las particulas y cristales (unidades de mundo, texto tamano 2.0, "Particle size" 100%)
    private static final int PARTICLE_MAX_PER_TEXT = 24;   // tope de particulas simultaneas en un solo texto

    // Efectos criticos disponibles (uno por estado de la bala). Los demas llegan en proximas entregas.
    private static final int FX_NONE = 0, FX_MELTING = 1, FX_FREEZING = 2, FX_CORRODED = 3, FX_WET = 4, FX_TARRED = 5;

    // --- Freezing: cristales de hielo en tres fases pulsantes, texto celeste y nieve ---
    // Fase 1: tres cristales pequenos. Fase 2: crecen y aparece otro pequeno. Fase 3: el central crece mas.
    private static final float ICE_PHASE2_AT = 0.30f, ICE_PHASE3_AT = 0.60f; // inicio de cada fase (fraccion de la vida del texto)
    private static final float ICE_PULSE_SECONDS = 0.22f;  // duracion del "pulso" de crecimiento al empezar cada fase
    private static final float ICE_ALPHA = 0.5f;           // opacidad de los cristales
    // Por cristal: x, y (fraccion del ancho/alto del texto), giro (grados) y tamano en fase 1, 2 y 3
    // (multiplos del tamano de referencia).
    private static final float[][] ICE_CRYSTALS = {
            {-0.46f, 0.10f, 22f, 0.40f, 0.62f, 0.62f},     // izquierdo
            {0.00f, 0.00f, -6f, 0.40f, 0.62f, 1.25f},      // central (el que mas crece)
            {0.46f, -0.10f, -24f, 0.40f, 0.62f, 0.62f},    // derecho
            {0.17f, 0.55f, 14f, 0.00f, 0.35f, 0.35f}       // pequeno que aparece en la fase 2
    };
    // Forma del cristal (hexagono alargado): vertices en orden punta, derecha arriba, derecha abajo,
    // punta inferior, izquierda abajo, izquierda arriba. x se multiplica por el ancho y y por el alto.
    private static final float[] ICE_LX = {0f, 1f, 0.85f, 0f, -0.85f, -1f};
    private static final float[] ICE_LY = {0.5f, 0.22f, -0.30f, -0.5f, -0.30f, 0.22f};
    private static final float[] ICE_TONE = {0f, 0.28f, 0.8f, 1f, 0.8f, 0.28f}; // 0 = celeste, 1 = azul
    private static final Color ICE_LIGHT = Color.valueOf("b8ecff");
    private static final Color ICE_DEEP = Color.valueOf("3f7dff");
    private static final Color TEXT_ICE = Color.valueOf("aee9ff");
    private static final Color GLOW_ICE_BLUE = new Color(0.25f, 0.55f, 1f, 0.14f);
    private static final Color GLOW_ICE_CYAN = new Color(0.6f, 0.92f, 1f, 0.12f);
    private static final float FREEZE_GLOW_OFFSET = 0.4f;  // separacion del glow (unidades, texto tamano 2.0)
    // Nieve: particulas blancas de baja opacidad que caen sin parar
    private static final float SNOW_LIFE_MIN = 0.8f, SNOW_LIFE_MAX = 1.6f;
    private static final float SNOW_FALL = 5f;             // unidades por segundo (texto tamano 2.0)
    private static final float SNOW_SWAY = 0.9f;           // vaiven lateral
    private static final float SNOW_ALPHA = 0.45f;
    private static final float SNOW_SIZE = 0.38f;          // radio base

    // --- Corroded: texto claro con glow verde grisaceo en orbita, gases verde menta y gotas con contorno ---
    private static final Color TEXT_CORRODED = Color.valueOf("e4ffd6");
    private static final Color GLOW_CORRODED = Color.valueOf("76a781");
    private static final Color DROP_FILL = Color.valueOf("e4ffd6");
    private static final Color GAS_MINT = Color.valueOf("8cffc4");
    // Glow: copias del texto en un anillo; la mas brillante da vueltas. El radio tiene que pasar el
    // contorno negro del texto, si no el glow queda tapado por el.
    private static final float CORR_GLOW_RADIUS = 1.8f;    // hasta donde llega (unidades, texto tamano 2.0)
    private static final float CORR_GLOW_ALPHA = 0.3f;     // brillo de la copia mas fuerte
    private static final float CORR_GLOW_SPEED = 1.2f;     // velocidad con la que da vueltas
    private static final int CORR_GLOW_SAMPLES = 8;
    // Gases: puffs circulares que salen de vez en cuando (hay una pausa al azar entre uno y otro)
    private static final float GAS_LIFE_MIN = 0.9f, GAS_LIFE_MAX = 1.6f;
    private static final float GAS_DELAY_MAX = 1.1f;       // pausa maxima antes de que renazca un gas
    private static final float GAS_SIZE = 0.28f;           // radio final (fraccion del tamano de referencia)
    private static final float GAS_RISE = 3f;              // cuanto suben (unidades por segundo)
    private static final float GAS_ALPHA = 0.32f;
    // Gotas: nacen sobre las letras creciendo muy despacio y despues bajan lentisimo
    private static final float DROP_LIFE_MIN = 1.1f, DROP_LIFE_MAX = 1.6f;
    private static final float DROP_GROW = 0.5f;           // fraccion del ciclo que pasa creciendo; el resto cae
    private static final float DROP_SIZE = 0.06f;          // radio final (fraccion del tamano de referencia); son gotitas
    private static final float DROP_FALL = 2.5f;           // distancia que bajan (unidades, texto tamano 2.0)
    private static final float DROP_ALPHA = 0.95f;

    // --- Wet: texto azul real, muchas burbujas casi quietas y gotas de agua que caen de vez en cuando ---
    private static final Color TEXT_WET = Color.valueOf("4169e1");
    private static final Color WET_BUBBLE = Color.valueOf("7a96ea");      // un poco mas claro que el texto
    private static final Color WET_BUBBLE_DEEP = Color.valueOf("3552c4"); // azul del degradado de abajo a la derecha
    private static final float WET_BUBBLE_ALPHA = 0.5f;
    private static final float WET_BUBBLE_MIN = 0.05f, WET_BUBBLE_MAX = 0.11f; // radio (fraccion del tamano de referencia)
    private static final float WET_WOBBLE = 0.22f;         // cuanto se mueven sin salirse de su punto (unidades, texto tamano 2.0)
    private static final float WET_APPEAR_SECONDS = 0.25f; // cada burbuja nace creciendo
    private static final float WET_APPEAR_MAX_DELAY = 0.35f;
    private static final int WET_DETACH_MIN = 1, WET_DETACH_MAX = 3; // burbujas que se sueltan al acabarse el texto
    private static final float WET_DETACH_LIFE_MIN = 0.3f, WET_DETACH_LIFE_MAX = 0.6f;
    private static final float WET_DETACH_WOBBLE = 0.2f;   // movimiento de las burbujas sueltas (unidades)
    private static final int WET_SEG = 10;                 // lados con los que se dibuja una burbuja
    private static final float[] WET_COS = new float[WET_SEG], WET_SIN = new float[WET_SEG];
    static {
        for (int i = 0; i < WET_SEG; i++) {
            WET_COS[i] = (float) Math.cos(i * Math.PI * 2.0 / WET_SEG);
            WET_SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / WET_SEG);
        }
    }
    private static final float WETDROP_LIFE_MIN = 0.7f, WETDROP_LIFE_MAX = 1.1f;
    private static final float WETDROP_DELAY_MAX = 1.2f;   // pausa maxima antes de que salga otra gota
    private static final float WETDROP_GROW = 0.3f;        // fraccion del ciclo que pasa formandose
    private static final float WETDROP_SIZE = 0.07f;       // radio final (fraccion del tamano de referencia)
    private static final float WETDROP_FALL = 7f;          // distancia que caen (unidades, texto tamano 2.0)
    private static final float WETDROP_ALPHA = 0.85f;

    // --- Tarred: texto y brea 222222, movimiento a la mitad, letras a distinta velocidad y gotas que se aplastan ---
    private static final Color TEXT_TAR = Color.valueOf("222222");
    private static final float TAR_MOTION_SPEED = 0.5f;    // el desplazamiento y las animaciones van a la mitad
    private static final float TAR_SPEED_VARIATION = 0.24f; // cada letra se desplaza entre -12 % y +12 % respecto al resto
    private static final float TAR_LIFE_MIN = 1.1f, TAR_LIFE_MAX = 1.5f; // ciclo de una gota: formarse, caer, aplastarse
    private static final float TAR_DELAY_MAX = 1.6f;       // pausa maxima antes de que salga otra gota (muy espaciadas)
    private static final float TAR_FORM = 0.15f, TAR_FALL = 0.60f; // fracciones del ciclo; el resto es el aplastado en el suelo
    private static final float TAR_DROP_SIZE = 0.09f;      // radio de la gota (fraccion del tamano de referencia)
    private static final float TAR_GROUND = 4f;            // a cuanto queda el suelo ficticio, bajo el texto (unidades, texto tamano 2.0)
    private static final float TAR_SPLAT_GROW = 1.3f;      // cuanto se ensancha el ovalo al aplastarse (veces el radio)
    private static final float TAR_SPLAT_FLAT = 0.38f;     // alto del ovalo respecto a su ancho
    private static final int TAR_SEG = 14;                 // lados con los que se dibuja un ovalo
    private static final float[] TAR_COS = new float[TAR_SEG], TAR_SIN = new float[TAR_SEG];
    static {
        for (int i = 0; i < TAR_SEG; i++) {
            TAR_COS[i] = (float) Math.cos(i * Math.PI * 2.0 / TAR_SEG);
            TAR_SIN[i] = (float) Math.sin(i * Math.PI * 2.0 / TAR_SEG);
        }
    }

    // --- Melting: burbujas de lava en bucle, glow movil y texto "derretido" ---
    private static final float MELT_LIFE_MIN = 0.5f, MELT_LIFE_MAX = 1f; // duracion de cada burbuja (segundos)
    private static final int MELT_MIN_PARTICLES = 3;
    private static final float MELT_GLOW_OFFSET = 0.45f;   // distancia del glow al texto (unidades, texto tamano 2.0)
    private static final float MELT_GLOW_SPEED = 2.2f;     // velocidad con la que el glow da vueltas
    private static final float MELT_WOBBLE_SPEED = 1.3f;   // velocidad del derretido de cada letra (baja = mas lento)
    private static final float MELT_COLOR_SPEED = 1.8f;    // velocidad con la que el color recorre el texto
    private static final float MELT_DROOP = 0.8f;          // cuanto cae cada letra al derretirse
    // Destellos: puntos de luz suave sobre el texto que alternan entre rojo, naranja y amarillo
    private static final float SPARK_LIFE_MIN = 0.6f, SPARK_LIFE_MAX = 1.4f; // duracion de cada destello (segundos)
    private static final int SPARK_MIN = 4, SPARK_MAX = 16;
    private static final float SPARK_SIZE = 0.9f;          // radio base (unidades, texto tamano 2.0)
    private static final float SPARK_RISE = 1.5f;          // cuanto suben mientras brillan (unidades por segundo)
    private static final float SPARK_COLOR_SPEED = 2.6f;   // velocidad con la que cambian de color
    private static final Color MELT_RED = Color.valueOf("ff6b4a");
    private static final Color MELT_ORANGE = Color.valueOf("ffa24a");
    private static final Color MELT_YELLOW = Color.valueOf("fff0b0");
    private static final Color MELT_GLOW_ORANGE = new Color(1f, 0.55f, 0.12f, 0.22f);
    private static final Color MELT_GLOW_YELLOW = new Color(1f, 0.85f, 0.3f, 0.18f);

    // Tipos de objetivo/estado para las claves de fusion
    private static final int KIND_UNIT_DAMAGE = 1, KIND_BUILD_DAMAGE = 2, KIND_STATUS = 3;

    // --- Configuracion de config.hjson (lo que NO esta en el menu) ---
    private boolean showAllyUnitDamage = true;
    private boolean showEnemyUnitDamage = true;
    private boolean showAllyBuildingDamage = true;
    private boolean showEnemyBuildingDamage = true;
    private boolean showShieldNulled = true;
    private float labelLifetimeTicks = 1.5f;
    private float iconSize = 8f;
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

        // Desplazamiento elegido (DISP_*) y direccion al azar de Random scatter, en radianes
        int displacement;
        float scatterAngle;

        // Efecto critico (FX_*), sus particulas y el texto letra por letra (solo con efecto)
        int critEffect = FX_NONE;
        Seq<Particle> particles;
        Seq<Particle> sparks;        // destellos de luz (cuentan para el limite de particulas)
        String[] charStrings;
        float[] charX;               // inicio de cada letra respecto al borde izquierdo, a escala 1
        float[] charSpeed;           // multiplicador de desplazamiento de cada letra (Tarred)
        float motionSpeed = 1f;      // 1 = normal; Tarred lo baja a la mitad
        final float fxSeed = Mathf.random(100f); // desfasa los movimientos de cada texto

        // Datos de fusion
        float sumRaw, sumTrue;       // dano acumulado (fusion de dano)
        boolean hasKey;
        long fusionKey;
    }

    // Una particula de un efecto critico, ligada a un texto
    private static class Particle {
        float offX, offY;            // posicion respecto al centro del texto, a escala 1
        float age, life;             // edad negativa = aun no nace
        float sizeMul;               // variacion de tamano
        float phase;                 // desfase propio (color y parpadeo de los destellos)
    }
    private final Seq<Popup> popups = new Seq<>();

    // Estado de las particulas (se actualiza cada frame)
    private float fxTime;                      // reloj de los efectos (no avanza en pausa)
    private float particleSizeMul = 1f;
    private int particleCap = Integer.MAX_VALUE;
    private int liveParticles;
    private final float[] dropPose = new float[4];
    private final float[] wetRim = new float[WET_SEG];     // colores de las burbujas de Wet (ya con opacidad)
    private float wetCenter;
    private final Pose detachPose = new Pose();
    private final Seq<Particle> orphanBubbles = new Seq<>(); // burbujas de Wet sueltas, en coordenadas de mundo
    private final float[] iceX = new float[6], iceY = new float[6], iceCl = new float[6], iceCr = new float[6];

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

    // Torreta de pruebas (solo sandbox): dispara liquidos que si hacen dano, para poder probar
    // los estados Tarred, Wet, Freezing y Corroded con los efectos criticos.
    public static LiquidTurret devTurret;

    private static final float DEV_TURRET_RANGE = 30f * 8f; // 30 casillas

    // Proyectil de liquido de la torreta: 45 de dano y el estado del liquido.
    private static LiquidBulletType devBullet(Liquid fluid, StatusEffect appliedStatus) {
        return new LiquidBulletType(fluid) {{
            damage = 45f;
            speed = 4f;
            lifetime = 64f;          // con 4 de velocidad y algo de friccion alcanza ~30 casillas
            drag = 0.001f;
            knockback = 0.8f;
            puddleSize = 8f;
            orbSize = 4f;
            ammoMultiplier = 0.4f;
            status = appliedStatus;
            statusDuration = 60f * 4f;
            layer = Layer.bullet - 2f;
        }};
    }

    @Override
    public void loadContent() {
        devTurret = new LiquidTurret("dev-turret") {{
            requirements(Category.turret, BuildVisibility.sandboxOnly, ItemStack.with(Items.copper, 1));
            alwaysUnlocked = true;
            localizedName = "Dev turret";
            description = "Sandbox turret that fires liquid projectiles dealing damage. "
                    + "Oil applies Tarred, Cryofluid applies Freezing, Water applies Wet and Arkycite applies Corroded.";

            ammo(
                    Liquids.oil, devBullet(Liquids.oil, StatusEffects.tarred),
                    Liquids.cryofluid, devBullet(Liquids.cryofluid, StatusEffects.freezing),
                    Liquids.water, devBullet(Liquids.water, StatusEffects.wet),
                    Liquids.arkycite, devBullet(Liquids.arkycite, StatusEffects.corroded)
            );

            size = 3;
            scaledHealth = 250f;
            reload = 6f;             // un proyectil cada 6 ticks = 10 por segundo
            inaccuracy = 3f;         // ligeramente dispersos
            velocityRnd = 0.08f;
            recoil = 1f;
            shootCone = 45f;
            liquidCapacity = 40f;
            shootEffect = Fx.shootLiquid;
            range = DEV_TURRET_RANGE;
        }

            // Usa los sprites de la tsunami de Mindustry (no lleva imagenes propias)
            @Override
            public void load() {
                super.load();
                region = Core.atlas.find("tsunami");
                if (drawer instanceof DrawTurret d) {
                    d.preview = region;
                    d.top = Core.atlas.find("tsunami-top");
                    d.liquid = Core.atlas.find("tsunami-liquid");
                }
            }

            @Override
            public void loadIcon() {
                super.loadIcon();
                fullIcon = Core.atlas.find("block-tsunami-full");
                uiIcon = Core.atlas.find("block-tsunami-ui");
            }

            @Override
            public void createIcons(MultiPacker packer) {
                // Sin iconos propios: se usan los de la tsunami
            }
        };
    }

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
            orphanBubbles.clear();
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
            if (popups.isEmpty() && orphanBubbles.isEmpty()) return;

            // En pausa el tiempo no avanza: todo se queda congelado (sin flotar ni
            // desaparecer), pero se sigue dibujando para que no se esfume.
            float dt = Vars.state.isPaused() ? 0f : Time.delta / 60f;

            // Mismo estado de dibujo que usa el juego para sus textos de mundo: capa por
            // encima de unidades y efectos, y fuera del pixelado si esta activo.
            float oldZ = Draw.z();
            Draw.z(Layer.playerName + 1f);
            Drawf.text();

            Rect view = Core.camera.bounds(Tmp.r1);

            // Ajustes de particulas (se leen una vez por frame) y total de particulas vivas
            fxTime += dt;
            particleSizeMul = readParticleSize();
            particleCap = readParticleCap();
            liveParticles = 0;
            for (int i = 0; i < popups.size; i++) {
                Popup q = popups.get(i);
                if (q.particles != null) liveParticles += q.particles.size;
                if (q.sparks != null) liveParticles += q.sparks.size;
            }

            // De los mas nuevos a los mas viejos: si se llega al tope, los nuevos tienen prioridad
            for (int i = popups.size - 1; i >= 0; i--) {
                Popup p = popups.get(i);

                p.age += dt;
                if (p.age >= p.lifetime) {
                    if (p.critEffect == FX_WET) detachWetBubbles(p);
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

                float textCx = p.anchorX + pose.dx + p.textDx * pose.scale;

                if (p.icon != null) {
                    // Reset ANTES de dibujar: limpia cualquier "mixcol" (tinte de
                    // destello) que haya dejado un efecto anterior en este frame.
                    Draw.reset();
                    Draw.color(Color.white, pose.alpha);
                    float size = iconSize * pose.scale;
                    Draw.rect(p.icon, p.anchorX + pose.dx + p.iconDx * pose.scale, y, size, size);
                    Draw.reset();
                }

                if (p.critEffect == FX_MELTING) drawMeltingText(p, textCx, y, pose.scale, pose.alpha);
                else if (p.critEffect == FX_FREEZING) drawFrozenText(p, textCx, y, pose.scale, pose.alpha);
                else if (p.critEffect == FX_CORRODED) drawCorrodedText(p, textCx, y, pose.scale, pose.alpha);
                else if (p.critEffect == FX_WET) drawText(p, textCx, y, pose.scale, pose.alpha, TEXT_WET);
                else if (p.critEffect == FX_TARRED) drawTarredText(p, textCx, y, pose);
                else drawText(p, textCx, y, pose.scale, pose.alpha);

                // Los cristales de hielo van por delante del texto (al 50 % de opacidad)
                if (p.critEffect == FX_FREEZING) drawIceCrystals(p, textCx, y, pose);

                if (p.critEffect != FX_NONE) updateParticles(p, dt, textCx, y, pose);
            }

            // Burbujas de Wet que se soltaron del texto: viven un instante mas y desaparecen de golpe
            if (!orphanBubbles.isEmpty()) {
                setWetColors(WET_BUBBLE_ALPHA);
                for (int i = orphanBubbles.size - 1; i >= 0; i--) {
                    Particle o = orphanBubbles.get(i);
                    o.age += dt;
                    if (o.age >= o.life) {
                        orphanBubbles.remove(i);
                        continue;
                    }
                    float ox = o.offX + Mathf.sin(fxTime * 1.6f + o.phase) * WET_DETACH_WOBBLE;
                    float oy = o.offY + Mathf.cos(fxTime * 1.3f + o.phase * 1.7f) * WET_DETACH_WOBBLE;
                    if (view.overlaps(ox - o.sizeMul, oy - o.sizeMul, o.sizeMul * 2f, o.sizeMul * 2f)) {
                        drawWetBubble(ox, oy, o.sizeMul, 1f);
                    }
                }
                Draw.reset();
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

                // Widget "Displacement effect": solo uno activo a la vez
                table.pref(displacementSetting());

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

                // Widget "Damage render": efectos criticos y particulas
                table.pref(headerSetting("Damage render", DAMAGE_RENDER_ICON_SPRITE));
                table.pref(check(KEY_CRIT_ENABLED, true,
                        "Critical effects", "When the damage reaches the threshold, the text gets the effect of its bullet's status (particles, glow and text style)."));
                table.pref(thresholdSetting());
                table.pref(percentSlider(KEY_PARTICLE_SCALE, PARTICLE_SCALE_DEFAULT, PARTICLE_SCALE_MIN, PARTICLE_SCALE_MAX,
                        "Particle size", "Size of the critical effect particles. They also follow the Text size setting."));
                table.pref(particleLimitSetting());
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

    // Selector de desplazamiento: encabezado con icono y, por cada opcion, un boton y su descripcion.
    private Setting displacementSetting() {
        Core.settings.defaults(KEY_DISPLACEMENT, DISPLACEMENT_DEFAULT);

        return new Setting(KEY_DISPLACEMENT) {
            @Override
            public void add(SettingsTable table) {
                float width = Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f));

                addHeader(table, "Displacement effect", DISPLACEMENT_ICON_SPRITE);

                ButtonGroup<TextButton> group = new ButtonGroup<>();
                String[][] options = {
                        {"Standard", "standard", "The text stays still for a moment, then floats upward."},
                        {"Ejected casing", "ejection", "The text flies up and to the right in an arc, then falls, like an ejected shell casing."},
                        {"Continuous wave", "wave", "The text rises along a smooth zigzag, starting from right to left. It slows down at every turn and speeds up again, like a vehicle taking curves."},
                        {"Random scatter", "scatter", "The text bursts out from the center in a random direction and stays still. While active, Damage text fusion and Status text fusion are turned off."}
                };
                for (String[] option : options) {
                    String value = option[1];
                    table.button(option[0], Styles.flatTogglet, () -> Core.settings.put(KEY_DISPLACEMENT, value))
                            .group(group)
                            .checked(b -> displacementMode().equals(value))
                            .size(width, 40f).left().padTop(4f).row();
                    table.add(option[2]).wrap().width(width).left().color(Color.lightGray).padTop(2f).padBottom(4f).row();
                }
            }
        };
    }

    // Umbral de dano para el efecto critico: un solo campo numerico que cambia de valor y de
    // titulo segun "Damage text fusion" este activa (texto fusionado) o no.
    private Setting thresholdSetting() {
        Core.settings.defaults(KEY_CRIT_FUSED, CRIT_FUSED_DEFAULT);
        Core.settings.defaults(KEY_CRIT_SINGLE, CRIT_SINGLE_DEFAULT);

        return new Setting(KEY_CRIT_SINGLE) {
            @Override
            public void add(SettingsTable table) {
                float width = Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f));
                int[] shownMode = {-1}; // -1 = aun no se mostro, 0 = sin fusion, 1 = fusionado

                table.table(row -> {
                    row.left();
                    Label label = row.add("").left().growX().get();
                    TextField field = row.field("", TextFieldFilter.digitsOnly, str -> {
                        if (str.isEmpty()) return;
                        try {
                            int v = Mathf.clamp(Integer.parseInt(str), 1, CRIT_MAX);
                            Core.settings.put(damageFusion() ? KEY_CRIT_FUSED : KEY_CRIT_SINGLE, v);
                        } catch (NumberFormatException ignored) {
                        }
                    }).width(120f).padLeft(4f).get();

                    // Cambia titulo y valor cuando se activa o desactiva la fusion de dano
                    row.update(() -> {
                        int mode = damageFusion() ? 1 : 0;
                        if (mode == shownMode[0]) return;
                        shownMode[0] = mode;
                        label.setText(mode == 1 ? "Critical threshold (fused text)" : "Critical threshold (unfused text)");
                        field.setText(String.valueOf(critThreshold(mode == 1)));
                    });
                }).width(width).left().padTop(4f).row();

                table.add("Minimum damage for the critical effect to appear (1 to " + CRIT_MAX + "). "
                                + "Fused text and unfused text each keep their own value; only the one that applies right now is shown.")
                        .wrap().width(width).left().color(Color.lightGray).padTop(4f).padBottom(6f).row();
            }
        };
    }

    // Limite de particulas vivas a la vez: una lista de botones con su descripcion.
    private Setting particleLimitSetting() {
        Core.settings.defaults(KEY_PARTICLE_LIMIT, PARTICLE_LIMIT_DEFAULT);

        return new Setting(KEY_PARTICLE_LIMIT) {
            @Override
            public void add(SettingsTable table) {
                float width = Math.min(500f, Core.graphics.getWidth() / 1.2f / Scl.scl(1f));

                table.add("Particle limit").left().padTop(10f).row();

                ButtonGroup<TextButton> group = new ButtonGroup<>();
                String[][] options = {
                        {"Performance", "performance", "Up to 100 particles at once."},
                        {"Regular", "regular", "Up to 300 particles at once."},
                        {"Demanding", "demanding", "Up to 600 particles at once."},
                        {"Extreme", "extreme", "No limit. It can slow the game down when many critical texts appear together."}
                };
                for (String[] option : options) {
                    String value = option[1];
                    table.button(option[0], Styles.flatTogglet, () -> Core.settings.put(KEY_PARTICLE_LIMIT, value))
                            .group(group)
                            .checked(b -> particleLimitMode().equals(value))
                            .size(width, 40f).left().padTop(4f).row();
                    table.add(option[2]).wrap().width(width).left().color(Color.lightGray).padTop(2f).padBottom(4f).row();
                }
            }
        };
    }

    private SliderSetting percentSlider(String key, int def, int min, int max, String title, String description) {
        Core.settings.defaults(key, def);
        SliderSetting s = new SliderSetting(key, def, min, max, 5, i -> i + "%", null);
        s.title = title;
        s.description = description;
        return s;
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

    // Random scatter no es compatible con las fusiones de texto: mientras esta elegido,
    // ambas fusiones quedan desactivadas (sin tocar lo que marcaste en el menu).
    private boolean scatterActive() {
        return displacementMode().equals("scatter");
    }

    private boolean damageFusion() {
        return !scatterActive() && Core.settings.getBool(KEY_DAMAGE_FUSION, false);
    }

    private boolean statusFusion() {
        return !scatterActive() && Core.settings.getBool(KEY_STATUS_FUSION, true);
    }

    private boolean statusFlood() {
        return Core.settings.getBool(KEY_STATUS_FLOOD, true);
    }

    private boolean critEnabled() {
        return Core.settings.getBool(KEY_CRIT_ENABLED, true);
    }

    // Umbral de dano critico: "fused" para texto fusionado, o el de texto sin fusionar.
    private int critThreshold(boolean fused) {
        return Mathf.clamp(Core.settings.getInt(fused ? KEY_CRIT_FUSED : KEY_CRIT_SINGLE,
                fused ? CRIT_FUSED_DEFAULT : CRIT_SINGLE_DEFAULT), 1, CRIT_MAX);
    }

    private String particleLimitMode() {
        String v = Core.settings.getString(KEY_PARTICLE_LIMIT, PARTICLE_LIMIT_DEFAULT);
        return (v.equals("performance") || v.equals("regular") || v.equals("demanding") || v.equals("extreme"))
                ? v : PARTICLE_LIMIT_DEFAULT;
    }

    // Tope de particulas vivas a la vez segun el limite elegido.
    private int readParticleCap() {
        switch (particleLimitMode()) {
            case "performance": return 100;
            case "demanding": return 600;
            case "extreme": return Integer.MAX_VALUE;
            default: return 300;
        }
    }

    private float readParticleSize() {
        return Mathf.clamp(Core.settings.getInt(KEY_PARTICLE_SCALE, PARTICLE_SCALE_DEFAULT),
                PARTICLE_SCALE_MIN, PARTICLE_SCALE_MAX) / 100f;
    }

    // Desplazamiento elegido en el menu: "standard", "ejection", "wave" o "scatter".
    private String displacementMode() {
        String v = Core.settings.getString(KEY_DISPLACEMENT, DISPLACEMENT_DEFAULT);
        return (v.equals("standard") || v.equals("ejection") || v.equals("wave") || v.equals("scatter"))
                ? v : DISPLACEMENT_DEFAULT;
    }

    private int displacementId() {
        switch (displacementMode()) {
            case "ejection": return DISP_EJECTION;
            case "wave": return DISP_WAVE;
            case "scatter": return DISP_SCATTER;
            default: return DISP_STANDARD;
        }
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
                applyCritical(existing, type, criticalValue(existing.sumRaw, existing.sumTrue), true);
                return;
            }

            Popup created = spawnPair(formatDamageText(raw, trueDamage), teamColor(isAlly),
                    pickIcon(type, trueDamage), x, y);
            created.sumRaw = raw;
            created.sumTrue = trueDamage;
            created.hasKey = true;
            created.fusionKey = key;
            fusionIndex.put(key, created);
            applyCritical(created, type, criticalValue(raw, trueDamage), true);
            return;
        }

        Popup single = spawnPair(formatDamageText(raw, trueDamage), teamColor(isAlly), pickIcon(type, trueDamage), x, y);
        applyCritical(single, type, criticalValue(raw, trueDamage), false);
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
        p.charStrings = null; // el texto pudo cambiar: se vuelve a medir letra por letra

        p.bounce = animBounce();
        p.fadeIn = animFadeIn();
        p.fadeOut = animFadeOut();
        p.grow = animGrow();
        p.shrink = animShrink();
        p.displacement = displacementId();
        p.scatterAngle = Mathf.random(Mathf.PI2);

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
    // El desplazamiento es uno solo (el elegido en el menu); los efectos de animacion
    // se pueden combinar entre si y con cualquier desplazamiento.
    private void computePose(Popup p, Pose out) {
        // Reloj del movimiento y de las animaciones (Tarred lo hace correr a la mitad). Los
        // finales de vida (desvanecer, encoger) siguen la vida real del texto.
        float ta = p.age * p.motionSpeed;

        switch (p.displacement) {
            case DISP_EJECTION: {
                // Parabola hacia arriba y a la derecha que termina cayendo por debajo del origen.
                float apexTime = Math.max(EJECT_APEX_TIME * p.lifetime, 0.01f);
                float vy = 2f * EJECT_APEX_HEIGHT / apexTime;
                float gravity = vy / apexTime;
                out.dx = EJECT_DISTANCE * (ta / p.lifetime);
                out.dy = vy * ta - 0.5f * gravity * ta * ta;
                break;
            }
            case DISP_WAVE: {
                // Zigzag horizontal suave (coseno): arranca a la derecha y va hacia la izquierda,
                // frena en cada extremo y recupera la velocidad al cruzar el centro, como un
                // vehiculo tomando curvas. Mientras tanto sube como el desplazamiento Standard.
                out.dx = WAVE_AMPLITUDE * Mathf.cos(ta / WAVE_PERIOD * Mathf.PI2);
                out.dy = Math.max(0f, ta - holdSeconds) * riseSpeed;
                break;
            }
            case DISP_SCATTER: {
                // Sale del centro en una direccion al azar, frena al llegar y se queda quieto.
                float t = Mathf.clamp(ta / SCATTER_SECONDS, 0f, 1f);
                float eased = 1f - (1f - t) * (1f - t);
                out.dx = Mathf.cos(p.scatterAngle) * SCATTER_DISTANCE * eased;
                out.dy = Mathf.sin(p.scatterAngle) * SCATTER_DISTANCE * eased;
                break;
            }
            default: {
                out.dx = 0f;
                out.dy = Math.max(0f, ta - holdSeconds) * riseSpeed;
            }
        }

        float left = p.lifetime - p.age; // tiempo que le queda de vida

        // --- Opacidad: Fade in (al inicio) y Fade out (al final) ---
        float alpha = 1f;
        if (p.fadeIn) alpha = Math.min(alpha, Mathf.clamp(ta / FADE_IN_SECONDS, 0f, 1f));
        if (p.fadeOut) alpha = Math.min(alpha, Mathf.clamp(left / FADE_OUT_SECONDS, 0f, 1f));
        out.alpha = alpha;

        // --- Tamano: Grow / Shrink (inicio y final de la vida) y Bounce (en bucle) ---
        float scale = 1f;
        float introWindow = Math.min(GROW_INTRO_SECONDS, p.lifetime / 2f);
        float endWindow = Math.min(END_EFFECT_SECONDS, p.lifetime / 2f);

        // Inicio: con Grow nace pequeno y llega al tamano real
        if (p.grow && ta < introWindow) {
            scale = Mathf.lerp(GROW_START_SCALE, 1f, Mathf.clamp(ta / introWindow, 0f, 1f));
        }

        // Final: Shrink se encoge hasta 0; Grow (sin Shrink) sigue agrandandose
        if (left < endWindow) {
            float t = Mathf.clamp(1f - left / endWindow, 0f, 1f);
            if (p.shrink) scale *= Mathf.lerp(1f, 0f, t);
            else if (p.grow) scale *= Mathf.lerp(1f, GROW_END_SCALE, t);
        }

        // Bounce: 1.0 -> 1.4 -> 1.0 en bucle, multiplica al resto
        if (p.bounce) {
            float pulse = 0.5f - 0.5f * Mathf.cos(ta / BOUNCE_PERIOD * Mathf.PI2);
            scale *= 1f + (BOUNCE_MAX_SCALE - 1f) * pulse;
        }

        out.scale = Math.max(scale, 0f);
    }

    // Dibuja el texto centrado en (cx, cy), igual que WorldLabelComp.drawAt: misma
    // fuente con contorno, misma escala y misma alineacion; la opacidad va en el color.
    private void drawText(Popup p, float cx, float cy, float scale, float alpha) {
        drawText(p, cx, cy, scale, alpha, p.color);
    }

    private void drawText(Popup p, float cx, float cy, float scale, float alpha, Color color) {
        Font font = Fonts.outline;

        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.25f * p.fontSize * scale / Scl.scl(1f));

        drawColor.set(color);
        drawColor.a *= alpha;
        font.setColor(drawColor);

        // En drawAt, "y" es la parte de arriba del texto: se sube la mitad de su alto
        font.draw(p.text, cx, cy + p.textHeight * scale / 2f, 0f, Align.center, false);

        font.getData().setScale(1f);
        font.setColor(Color.white);
        font.setUseIntegerPositions(ints);
    }

    // =====================================================================
    //  Efectos criticos y particulas
    // =====================================================================

    // Numero que se compara con el umbral: el crudo en modo "raw"; en "true" y "both",
    // el dano verdadero (lo que realmente se inflige).
    private float criticalValue(float raw, float trueDamage) {
        return damageMode().equals("raw") ? raw : trueDamage;
    }

    // Activa o quita el efecto critico de un texto segun su dano y el estado de su bala.
    private void applyCritical(Popup p, BulletType type, float value, boolean fused) {
        int effect = FX_NONE;
        if (critEnabled() && value >= critThreshold(fused)) effect = critEffectFor(type);
        p.motionSpeed = effect == FX_TARRED ? TAR_MOTION_SPEED : 1f; // Tarred: todo va a la mitad
        if (p.critEffect != effect) {
            p.critEffect = effect;
            if (p.particles != null) {
                liveParticles -= p.particles.size;
                p.particles.clear();
            }
            if (p.sparks != null) {
                liveParticles -= p.sparks.size;
                p.sparks.clear();
            }
        }
    }

    // Efecto critico segun el estado de la bala (los demas estados llegan mas adelante).
    private int critEffectFor(BulletType type) {
        if (type == null) return FX_NONE;
        if (type.status == StatusEffects.melting) return FX_MELTING;
        if (type.status == StatusEffects.freezing) return FX_FREEZING;
        if (type.status == StatusEffects.corroded) return FX_CORRODED;
        if (type.status == StatusEffects.wet) return FX_WET;
        if (type.status == StatusEffects.tarred) return FX_TARRED;
        return FX_NONE;
    }

    // Particulas del efecto critico de un texto (cx, cy = centro del texto).
    private void updateParticles(Popup p, float dt, float cx, float cy, Pose pose) {
        if (p.critEffect == FX_MELTING) updateMeltParticles(p, dt, cx, cy, pose);
        else if (p.critEffect == FX_FREEZING) updateSnow(p, dt, cx, cy, pose);
        else if (p.critEffect == FX_CORRODED) updateCorrosion(p, dt, cx, cy, pose);
        else if (p.critEffect == FX_WET) updateWet(p, dt, cx, cy, pose);
        else if (p.critEffect == FX_TARRED) updateTar(p, dt, cx, cy, pose);
    }

    // Melting: burbujas de lava en bucle y destellos de luz.
    private void updateMeltParticles(Popup p, float dt, float cx, float cy, Pose pose) {
        if (p.particles == null) p.particles = new Seq<>();

        float size = PARTICLE_BASE_SIZE * (p.fontSize / 2f) * particleSizeMul;
        // Mas ancho el texto (de 3 a 6 digitos), mas burbujas, repartidas a lo ancho
        int target = Mathf.clamp(Math.round(p.textWidth / (size * 0.6f)), MELT_MIN_PARTICLES, PARTICLE_MAX_PER_TEXT);
        float remaining = p.lifetime - p.age;
        boolean canSpawn = remaining >= MELT_LIFE_MIN * 0.5f;

        // Avanzar: al terminar su ciclo la burbuja renace en otro sitio (bucle) mientras al
        // texto le quede vida; si no, desaparece.
        for (int i = p.particles.size - 1; i >= 0; i--) {
            Particle pt = p.particles.get(i);
            pt.age += dt;
            if (pt.age < pt.life) continue;

            if (canSpawn && i < target && liveParticles <= particleCap) {
                spawnMeltParticle(p, pt, i, target, size, remaining);
            } else {
                p.particles.remove(i);
                liveParticles--;
            }
        }

        // Reponer hasta el objetivo, respetando el limite global de particulas
        while (canSpawn && p.particles.size < target && liveParticles < particleCap) {
            Particle pt = new Particle();
            spawnMeltParticle(p, pt, p.particles.size, target, size, remaining);
            pt.age = -Mathf.random(MELT_LIFE_MAX); // nacen desfasadas para que no revienten a la vez
            p.particles.add(pt);
            liveParticles++;
        }

        // Dibujar
        for (int i = 0; i < p.particles.size; i++) {
            Particle pt = p.particles.get(i);
            if (pt.age < 0f) continue;

            float t = Mathf.clamp(pt.age / pt.life, 0f, 1f);
            float px = cx + pt.offX * pose.scale;
            float py = cy + pt.offY * pose.scale;
            float s = size * pt.sizeMul * pose.scale;

            drawLavaBubble(px, py, s, t, pose.alpha);
        }

        updateSparks(p, dt, cx, cy, pose, size, remaining);
        Draw.reset();
    }

    // Destellos de Melting: puntos de luz suave sobre las letras. Nacen, suben un poco y se
    // apagan, cambiando entre rojo, naranja y amarillo. Se dibujan sumando luz.
    private void updateSparks(Popup p, float dt, float cx, float cy, Pose pose, float size, float remaining) {
        if (p.sparks == null) p.sparks = new Seq<>();

        float sizeFactor = p.fontSize / 2f * particleSizeMul;
        int target = Mathf.clamp(Math.round(p.textWidth / (size * 0.45f)), SPARK_MIN, SPARK_MAX);
        boolean canSpawn = remaining >= SPARK_LIFE_MIN * 0.5f;

        for (int i = p.sparks.size - 1; i >= 0; i--) {
            Particle sp = p.sparks.get(i);
            sp.age += dt;
            if (sp.age < sp.life) continue;

            if (canSpawn && i < target && liveParticles <= particleCap) {
                spawnSpark(p, sp, i, target, remaining);
            } else {
                p.sparks.remove(i);
                liveParticles--;
            }
        }

        while (canSpawn && p.sparks.size < target && liveParticles < particleCap) {
            Particle sp = new Particle();
            spawnSpark(p, sp, p.sparks.size, target, remaining);
            sp.age = -Mathf.random(SPARK_LIFE_MAX); // nacen desfasados
            p.sparks.add(sp);
            liveParticles++;
        }

        Draw.blend(Blending.additive);
        for (int i = 0; i < p.sparks.size; i++) {
            Particle sp = p.sparks.get(i);
            if (sp.age < 0f) continue;

            float t = Mathf.clamp(sp.age / sp.life, 0f, 1f);
            float fade = Mathf.sin(t * Mathf.PI);                                  // aparece y se apaga
            float twinkle = 0.75f + 0.25f * Mathf.sin(fxTime * 9f + sp.phase * 3f); // parpadeo suave
            float a = fade * twinkle * pose.alpha;
            if (a <= 0.01f) continue;

            meltingPalette(0.5f + 0.5f * Mathf.sin(fxTime * SPARK_COLOR_SPEED + sp.phase), drawColor);

            float px = cx + sp.offX * pose.scale;
            float py = cy + (sp.offY + sp.age * SPARK_RISE * sizeFactor) * pose.scale;
            float r = SPARK_SIZE * sizeFactor * sp.sizeMul * pose.scale;

            Draw.color(drawColor, a * 0.28f);
            Fill.circle(px, py, r * 2.4f);       // halo
            Draw.color(drawColor, a * 0.7f);
            Fill.circle(px, py, r * 1.2f);       // luz
            Draw.color(Color.white, a * 0.6f);
            Fill.circle(px, py, r * 0.5f);       // centro brillante
        }
        Draw.blend();
        Draw.reset();
    }

    // Coloca un destello nuevo en su franja a lo ancho del texto, a una altura al azar.
    private void spawnSpark(Popup p, Particle sp, int slot, int slots, float remaining) {
        int n = Math.max(slots, 1);
        float halfW = p.textWidth / 2f;
        float halfH = p.textHeight / 2f;
        float slotW = halfW * 2f / n;

        sp.offX = -halfW + ((slot % n) + Mathf.random()) * slotW;
        sp.offY = Mathf.random(-halfH, halfH);
        sp.sizeMul = Mathf.random(0.6f, 1.3f);
        sp.phase = Mathf.random(Mathf.PI2);
        sp.life = Math.max(Math.min(Mathf.random(SPARK_LIFE_MIN, SPARK_LIFE_MAX), remaining), 0.05f);
        sp.age = 0f;
    }

    // Coloca una burbuja de lava nueva: dentro de su "franja" a lo ancho del texto, a una altura al azar.
    private void spawnMeltParticle(Popup p, Particle pt, int slot, int slots, float size, float remaining) {
        int n = Math.max(slots, 1);
        float halfW = p.textWidth / 2f + size * 0.15f;
        float halfH = p.textHeight / 2f + size * 0.2f;
        float slotW = halfW * 2f / n;

        pt.offX = -halfW + ((slot % n) + Mathf.random()) * slotW;
        pt.offY = Mathf.random(-halfH, halfH);
        pt.sizeMul = Mathf.random(0.7f, 1.2f);
        pt.life = Math.max(Math.min(Mathf.random(MELT_LIFE_MIN, MELT_LIFE_MAX), remaining), 0.05f);
        pt.age = 0f;
    }

    // Burbuja de lava: crece, y al final revienta en un aro que se desvanece.
    private void drawLavaBubble(float x, float y, float size, float t, float alpha) {
        Draw.reset();
        if (t < 0.65f) {
            float k = t / 0.65f;
            Draw.color(MELT_ORANGE, alpha * 0.85f);
            Fill.circle(x, y, size * 0.28f * (0.4f + 0.6f * k));
            Draw.color(MELT_YELLOW, alpha * 0.8f);
            Fill.circle(x - size * 0.05f, y + size * 0.05f, size * 0.12f * k);
        } else {
            float k = (t - 0.65f) / 0.35f;
            Draw.color(MELT_YELLOW, alpha * (1f - k));
            Lines.stroke(Math.max(0.2f, size * 0.04f * (1f - k)));
            Lines.circle(x, y, size * (0.3f + 0.25f * k));
        }
        Draw.reset();
    }

    // Pulso de crecimiento: sube pasandose un poco del tamano final y se asienta (x de 0 a 1).
    private static float backOut(float x) {
        float k = x - 1f;
        return 1f + 2.70158f * k * k * k + 1.70158f * k * k;
    }

    // Cristales de Freezing, detras del texto. Cada fase empieza con un pulso de crecimiento.
    private void drawIceCrystals(Popup p, float cx, float cy, Pose pose) {
        float ref = PARTICLE_BASE_SIZE * (p.fontSize / 2f) * particleSizeMul;
        float t2 = ICE_PHASE2_AT * p.lifetime;
        float t3 = ICE_PHASE3_AT * p.lifetime;

        float k1 = backOut(Mathf.clamp(p.age / ICE_PULSE_SECONDS, 0f, 1f));
        float k2 = backOut(Mathf.clamp((p.age - t2) / ICE_PULSE_SECONDS, 0f, 1f));
        float k3 = backOut(Mathf.clamp((p.age - t3) / ICE_PULSE_SECONDS, 0f, 1f));

        for (float[] c : ICE_CRYSTALS) {
            float size = c[3] * k1 + (c[4] - c[3]) * k2 + (c[5] - c[4]) * k3;
            if (size <= 0.01f) continue;
            drawIceCrystal(cx + c[0] * p.textWidth * pose.scale, cy + c[1] * p.textHeight * pose.scale,
                    ref * size * pose.scale, c[2], pose.alpha);
        }
        Draw.reset();
    }

    // Un cristal: hexagono alargado en dos facetas con degradado de celeste (arriba) a azul (abajo),
    // al 50 % de opacidad, con lineas blancas en el borde y en sus aristas.
    private void drawIceCrystal(float x, float y, float h, float rotDeg, float alpha) {
        float w = h * 0.28f;
        float rad = rotDeg * Mathf.PI / 180f;
        float cs = Mathf.cos(rad), sn = Mathf.sin(rad);
        float fill = ICE_ALPHA * alpha;

        for (int i = 0; i < 6; i++) {
            float lx = ICE_LX[i] * w, ly = ICE_LY[i] * h;
            iceX[i] = x + lx * cs - ly * sn;
            iceY[i] = y + lx * sn + ly * cs;

            Tmp.c1.set(ICE_LIGHT).lerp(ICE_DEEP, ICE_TONE[i]);
            iceCl[i] = Color.toFloatBits(Tmp.c1.r, Tmp.c1.g, Tmp.c1.b, fill);
            // La faceta derecha es un poco mas oscura para dar volumen
            Tmp.c1.set(ICE_LIGHT).lerp(ICE_DEEP, Math.min(ICE_TONE[i] + 0.2f, 1f));
            iceCr[i] = Color.toFloatBits(Tmp.c1.r, Tmp.c1.g, Tmp.c1.b, fill);
        }

        // Faceta izquierda (punta, izq. arriba, izq. abajo, punta inferior) y derecha
        Fill.quad(iceX[0], iceY[0], iceCl[0], iceX[5], iceY[5], iceCl[5],
                iceX[4], iceY[4], iceCl[4], iceX[3], iceY[3], iceCl[3]);
        Fill.quad(iceX[0], iceY[0], iceCr[0], iceX[1], iceY[1], iceCr[1],
                iceX[2], iceY[2], iceCr[2], iceX[3], iceY[3], iceCr[3]);

        // Lineas blancas: contorno, arista central y los cortes de las facetas
        Draw.color(Color.white, alpha * 0.85f);
        Lines.stroke(Math.max(0.25f, h * 0.035f));
        for (int i = 0; i < 6; i++) {
            int j = (i + 1) % 6;
            Lines.line(iceX[i], iceY[i], iceX[j], iceY[j]);
        }
        Lines.line(iceX[0], iceY[0], iceX[3], iceY[3]);
        float mx = x + (0f * cs - (-0.16f * h) * sn);
        float my = y + (0f * sn + (-0.16f * h) * cs);
        Lines.line(iceX[5], iceY[5], mx, my);
        Lines.line(iceX[1], iceY[1], mx, my);
    }

    // Texto de Freezing: celeste, con un glow muy ligero azul y celeste.
    private void drawFrozenText(Popup p, float cx, float cy, float scale, float alpha) {
        Font font = Fonts.outline;
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.25f * p.fontSize * scale / Scl.scl(1f));

        float g = FREEZE_GLOW_OFFSET * (p.fontSize / 2f) * scale;
        float top = cy + p.textHeight * scale / 2f;

        Draw.blend(Blending.additive);
        drawColor.set(GLOW_ICE_BLUE);
        drawColor.a *= alpha;
        font.setColor(drawColor);
        font.draw(p.text, cx - g, top, 0f, Align.center, false);
        drawColor.set(GLOW_ICE_CYAN);
        drawColor.a *= alpha;
        font.setColor(drawColor);
        font.draw(p.text, cx + g, top, 0f, Align.center, false);
        Draw.blend();

        font.getData().setScale(1f);
        font.setColor(Color.white);
        font.setUseIntegerPositions(ints);

        drawText(p, cx, cy, scale, alpha, TEXT_ICE);
    }

    // Nieve de Freezing: puntos blancos de baja opacidad que nacen sobre el texto y caen
    // sin parar, meciendose un poco. Cuentan para el limite de particulas.
    private void updateSnow(Popup p, float dt, float cx, float cy, Pose pose) {
        if (p.particles == null) p.particles = new Seq<>();

        float sizeFactor = p.fontSize / 2f * particleSizeMul;
        float size = PARTICLE_BASE_SIZE * sizeFactor;
        int target = Mathf.clamp(Math.round(p.textWidth / (size * 0.5f)), 5, 18);
        float remaining = p.lifetime - p.age;
        boolean canSpawn = remaining >= SNOW_LIFE_MIN * 0.5f;

        for (int i = p.particles.size - 1; i >= 0; i--) {
            Particle pt = p.particles.get(i);
            pt.age += dt;
            if (pt.age < pt.life) continue;

            if (canSpawn && i < target && liveParticles <= particleCap) {
                spawnSnow(p, pt, i, target, size, remaining);
            } else {
                p.particles.remove(i);
                liveParticles--;
            }
        }

        while (canSpawn && p.particles.size < target && liveParticles < particleCap) {
            Particle pt = new Particle();
            spawnSnow(p, pt, p.particles.size, target, size, remaining);
            pt.age = -Mathf.random(SNOW_LIFE_MAX); // nacen desfasados
            p.particles.add(pt);
            liveParticles++;
        }

        for (int i = 0; i < p.particles.size; i++) {
            Particle pt = p.particles.get(i);
            if (pt.age < 0f) continue;

            float t = Mathf.clamp(pt.age / pt.life, 0f, 1f);
            float a = SNOW_ALPHA * Mathf.sin(t * Mathf.PI) * pose.alpha; // aparece y se apaga
            if (a <= 0.01f) continue;

            float px = cx + (pt.offX + Mathf.sin(pt.age * 2f + pt.phase) * SNOW_SWAY * sizeFactor) * pose.scale;
            float py = cy + (pt.offY - pt.age * SNOW_FALL * sizeFactor) * pose.scale;
            Draw.color(Color.white, a);
            Fill.circle(px, py, SNOW_SIZE * sizeFactor * pt.sizeMul * pose.scale);
        }
        Draw.reset();
    }

    // Coloca un copo nuevo en su franja a lo ancho del texto, desde la mitad de arriba hacia afuera.
    private void spawnSnow(Popup p, Particle pt, int slot, int slots, float size, float remaining) {
        int n = Math.max(slots, 1);
        float halfW = p.textWidth / 2f + size * 0.2f;
        float halfH = p.textHeight / 2f;
        float slotW = halfW * 2f / n;

        pt.offX = -halfW + ((slot % n) + Mathf.random()) * slotW;
        pt.offY = Mathf.random(-halfH * 0.3f, halfH + size * 0.4f);
        pt.sizeMul = Mathf.random(0.6f, 1.3f);
        pt.phase = Mathf.random(Mathf.PI2);
        pt.life = Math.max(Math.min(Mathf.random(SNOW_LIFE_MIN, SNOW_LIFE_MAX), remaining), 0.05f);
        pt.age = 0f;
    }

    // Texto de Corroded: claro, con un glow verde grisaceo que rodea las letras y cuyo punto mas
    // brillante da vueltas.
    private void drawCorrodedText(Popup p, float cx, float cy, float scale, float alpha) {
        Font font = Fonts.outline;
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.25f * p.fontSize * scale / Scl.scl(1f));

        float radius = CORR_GLOW_RADIUS * (p.fontSize / 2f) * scale;
        float orbit = fxTime * CORR_GLOW_SPEED + p.fxSeed;
        float top = cy + p.textHeight * scale / 2f;

        Draw.blend(Blending.additive);
        for (int i = 0; i < CORR_GLOW_SAMPLES; i++) {
            float ang = i * Mathf.PI2 / CORR_GLOW_SAMPLES;
            float lobe = 0.25f + 0.75f * (0.5f + 0.5f * Mathf.cos(ang - orbit)); // la copia cercana a "orbit" brilla mas
            drawColor.set(GLOW_CORRODED);
            drawColor.a = CORR_GLOW_ALPHA * lobe * alpha;
            font.setColor(drawColor);
            font.draw(p.text, cx + Mathf.cos(ang) * radius, top + Mathf.sin(ang) * radius * 0.8f, 0f, Align.center, false);
        }
        Draw.blend();

        font.getData().setScale(1f);
        font.setColor(Color.white);
        font.setUseIntegerPositions(ints);

        drawText(p, cx, cy, scale, alpha, TEXT_CORRODED);
    }

    // Corroded: gases (en particles) y gotas (en sparks). Los gases salen de vez en cuando y
    // suben ensanchandose; las gotas van naciendo sobre las letras, crecen despacio y bajan lento.
    private void updateCorrosion(Popup p, float dt, float cx, float cy, Pose pose) {
        if (p.particles == null) p.particles = new Seq<>();
        if (p.sparks == null) p.sparks = new Seq<>();

        float sizeFactor = p.fontSize / 2f * particleSizeMul;
        float size = PARTICLE_BASE_SIZE * sizeFactor;
        float remaining = p.lifetime - p.age;
        boolean canSpawn = remaining >= GAS_LIFE_MIN * 0.5f;
        int gasTarget = Mathf.clamp(Math.round(p.textWidth / (size * 0.9f)), 2, 8);
        int dropTarget = Mathf.clamp(Math.round(p.textWidth / (size * 0.16f)), 10, 32);

        // Gases
        for (int i = p.particles.size - 1; i >= 0; i--) {
            Particle pt = p.particles.get(i);
            pt.age += dt;
            if (pt.age < pt.life) continue;

            if (canSpawn && i < gasTarget && liveParticles <= particleCap) {
                spawnGas(p, pt, i, gasTarget, remaining, GAS_DELAY_MAX);
            } else {
                p.particles.remove(i);
                liveParticles--;
            }
        }
        while (canSpawn && p.particles.size < gasTarget && liveParticles < particleCap) {
            Particle pt = new Particle();
            spawnGas(p, pt, p.particles.size, gasTarget, remaining, GAS_DELAY_MAX * 1.5f);
            p.particles.add(pt);
            liveParticles++;
        }

        // Gotas
        for (int i = p.sparks.size - 1; i >= 0; i--) {
            Particle dp = p.sparks.get(i);
            dp.age += dt;
            if (dp.age < dp.life) continue;

            if (canSpawn && i < dropTarget && liveParticles <= particleCap) {
                spawnDrop(p, dp, i, dropTarget, remaining, 0.3f);
            } else {
                p.sparks.remove(i);
                liveParticles--;
            }
        }
        while (canSpawn && p.sparks.size < dropTarget && liveParticles < particleCap) {
            Particle dp = new Particle();
            spawnDrop(p, dp, p.sparks.size, dropTarget, remaining, DROP_LIFE_MAX * 0.8f);
            p.sparks.add(dp);
            liveParticles++;
        }

        // Gotas: solo van por encima del texto; el glow y el vapor se dibujan despues, sobre ellas
        for (int i = 0; i < p.sparks.size; i++) {
            Particle dp = p.sparks.get(i);
            if (!computeDrop(dp, cy, pose, size, sizeFactor)) continue;
            drawDrop(cx + dp.offX * pose.scale, dropPose[0], dropPose[1], dropPose[2], dropPose[3]);
        }

        // Glow de las gotas: suma luz sobre ellas y se mueve con el del texto
        float glowR = CORR_GLOW_RADIUS * sizeFactor * pose.scale;
        float orbit = fxTime * CORR_GLOW_SPEED + p.fxSeed;
        float gox = Mathf.cos(orbit) * glowR * 0.25f;
        float goy = Mathf.sin(orbit) * glowR * 0.25f;

        Draw.blend(Blending.additive);
        for (int i = 0; i < p.sparks.size; i++) {
            Particle dp = p.sparks.get(i);
            if (!computeDrop(dp, cy, pose, size, sizeFactor)) continue;
            Draw.color(GLOW_CORRODED, dropPose[3] * 0.5f);
            Fill.circle(cx + dp.offX * pose.scale + gox, dropPose[0] + goy, dropPose[1] * 2.2f);
        }
        Draw.blend();

        // Vapor de la corrosion: por encima de las gotas
        for (int i = 0; i < p.particles.size; i++) {
            Particle pt = p.particles.get(i);
            if (pt.age < 0f) continue;

            float t = Mathf.clamp(pt.age / pt.life, 0f, 1f);
            float a = GAS_ALPHA * Mathf.sin(t * Mathf.PI) * pose.alpha; // aparece y se disipa
            if (a <= 0.01f) continue;

            float gx = cx + (pt.offX + Mathf.sin(pt.age * 1.7f + pt.phase) * 0.6f * sizeFactor) * pose.scale;
            float gy = cy + (pt.offY + pt.age * GAS_RISE * sizeFactor) * pose.scale;
            float r = GAS_SIZE * size * pt.sizeMul * (0.35f + 0.65f * t) * pose.scale;

            Draw.color(GAS_MINT, a);
            Fill.circle(gx, gy, r);
            Draw.color(GAS_MINT, a * 0.8f);
            Fill.circle(gx + r * 0.2f, gy + r * 0.15f, r * 0.55f);
        }
        Draw.reset();
    }

    // Estado de una gota de Corroded en este instante (ver la version con parametros).
    private boolean computeDrop(Particle dp, float cy, Pose pose, float size, float sizeFactor) {
        return computeDrop(dp, cy, pose, size, sizeFactor, DROP_GROW, DROP_SIZE, DROP_FALL, DROP_ALPHA);
    }

    // Estado de una gota en este instante, en dropPose: [0] y, [1] radio, [2] cola, [3] opacidad.
    // Primero crece (grow = fraccion del ciclo); despues cae acelerando y se desvanece al final.
    // Devuelve false si no hay nada que dibujar.
    private boolean computeDrop(Particle dp, float cy, Pose pose, float size, float sizeFactor,
                                float grow, float sizeFrac, float fall, float alphaBase) {
        if (dp.age < 0f) return false;

        float t = Mathf.clamp(dp.age / dp.life, 0f, 1f);
        float r0 = sizeFrac * size * dp.sizeMul * pose.scale;
        float a = alphaBase * pose.alpha;
        float y = cy + dp.offY * pose.scale;
        float r, stretch;

        if (t < grow) {
            float k = t / grow;
            k = k * k * (3f - 2f * k); // arranca y termina de crecer suavemente
            r = r0 * k;
            stretch = 0.1f + 0.3f * k;
        } else {
            float f = (t - grow) / (1f - grow);
            r = r0;
            stretch = 0.4f + 0.6f * f;
            y -= fall * sizeFactor * f * f * pose.scale;
            if (f > 0.7f) a *= 1f - (f - 0.7f) / 0.3f;
        }

        if (a <= 0.01f || r < 0.05f) return false;
        dropPose[0] = y;
        dropPose[1] = r;
        dropPose[2] = stretch;
        dropPose[3] = a;
        return true;
    }

    // Una gotita: circulo con colita hacia arriba, sin contorno.
    private void drawDrop(float x, float y, float r, float stretch, float alpha) {
        Draw.color(DROP_FILL, alpha);
        Fill.circle(x, y, r);
        Fill.tri(x - r * 0.8f, y + r * 0.3f, x + r * 0.8f, y + r * 0.3f, x, y + r * (1.4f + stretch));
    }

    // Coloca un gas nuevo sobre el texto; nace tras una pausa al azar (maxDelay).
    private void spawnGas(Popup p, Particle pt, int slot, int slots, float remaining, float maxDelay) {
        int n = Math.max(slots, 1);
        float halfW = p.textWidth / 2f;
        float halfH = p.textHeight / 2f;
        float slotW = halfW * 2f / n;

        pt.offX = -halfW + ((slot % n) + Mathf.random()) * slotW;
        pt.offY = Mathf.random(-halfH * 0.5f, halfH * 0.8f);
        pt.sizeMul = Mathf.random(0.7f, 1.3f);
        pt.phase = Mathf.random(Mathf.PI2);
        pt.life = Math.max(Math.min(Mathf.random(GAS_LIFE_MIN, GAS_LIFE_MAX), remaining), 0.05f);
        pt.age = -Mathf.random(maxDelay);
    }

    // Coloca una gota nueva en un punto cualquiera de las letras; nace tras una pausa al azar.
    private void spawnDrop(Popup p, Particle dp, int slot, int slots, float remaining, float maxDelay) {
        int n = Math.max(slots, 1);
        float halfW = p.textWidth / 2f;
        float slotW = halfW * 2f / n;

        dp.offX = -halfW + ((slot % n) + Mathf.random()) * slotW;
        dp.offY = Mathf.random(-p.textHeight * 0.35f, p.textHeight * 0.35f);
        dp.sizeMul = Mathf.random(0.75f, 1.25f);
        dp.phase = Mathf.random(Mathf.PI2);
        dp.life = Math.max(Math.min(Mathf.random(DROP_LIFE_MIN, DROP_LIFE_MAX), remaining), 0.05f);
        dp.age = -Mathf.random(maxDelay);
    }

    // ---------------------------------------------------------------------
    //  Wet
    // ---------------------------------------------------------------------

    // Prepara los colores de una burbuja de Wet: celeste claro en el centro y un degradado azul
    // hacia la parte de abajo a la derecha (da volumen). alpha = opacidad de la burbuja.
    private void setWetColors(float alpha) {
        Tmp.c1.set(WET_BUBBLE);
        wetCenter = Color.toFloatBits(Tmp.c1.r, Tmp.c1.g, Tmp.c1.b, alpha);
        for (int i = 0; i < WET_SEG; i++) {
            // 1 en la esquina de abajo a la derecha (-45 grados), 0 en la de arriba a la izquierda
            float shade = 0.5f + 0.5f * (float) Math.cos(i * Math.PI * 2.0 / WET_SEG + Math.PI / 4.0);
            shade *= shade;
            Tmp.c1.set(WET_BUBBLE).lerp(WET_BUBBLE_DEEP, shade);
            wetRim[i] = Color.toFloatBits(Tmp.c1.r, Tmp.c1.g, Tmp.c1.b, alpha);
        }
    }

    // Una burbuja: abanico de triangulos con degradado y dos puntos blancos de luz.
    // Antes hay que llamar a setWetColors. lightAlpha = visibilidad de los puntos de luz.
    private void drawWetBubble(float x, float y, float r, float lightAlpha) {
        if (r < 0.05f) return;

        for (int i = 0; i < WET_SEG; i++) {
            int j = (i + 1) % WET_SEG;
            Fill.quad(x, y, wetCenter,
                    x + WET_COS[i] * r, y + WET_SIN[i] * r, wetRim[i],
                    x + WET_COS[j] * r, y + WET_SIN[j] * r, wetRim[j],
                    x, y, wetCenter);
        }

        Draw.color(Color.white, lightAlpha * 0.9f);
        Fill.circle(x - r * 0.35f, y + r * 0.38f, r * 0.22f);
        Draw.color(Color.white, lightAlpha * 0.55f);
        Fill.circle(x + r * 0.35f, y - r * 0.42f, r * 0.09f);
    }

    // Wet: muchas burbujas casi quietas en su punto (sin renacer) y gotas de agua que caen
    // de vez en cuando. Las burbujas van en particles y las gotas en sparks.
    private void updateWet(Popup p, float dt, float cx, float cy, Pose pose) {
        if (p.particles == null) p.particles = new Seq<>();
        if (p.sparks == null) p.sparks = new Seq<>();

        float sizeFactor = p.fontSize / 2f * particleSizeMul;
        float size = PARTICLE_BASE_SIZE * sizeFactor;
        float remaining = p.lifetime - p.age;

        // Burbujas: se reponen solo si el texto se hizo mas ancho o si habia limite de particulas
        int bubbleTarget = Mathf.clamp(Math.round(p.textWidth / (size * 0.18f)), 10, 30);
        while (p.particles.size < bubbleTarget && liveParticles < particleCap) {
            Particle b = new Particle();
            spawnWetBubble(p, b, p.particles.size, bubbleTarget, size);
            p.particles.add(b);
            liveParticles++;
        }

        setWetColors(WET_BUBBLE_ALPHA * pose.alpha);
        float wobble = WET_WOBBLE * sizeFactor;
        for (int i = 0; i < p.particles.size; i++) {
            Particle b = p.particles.get(i);
            b.age += dt;
            if (b.age <= 0f) continue;

            float k = Mathf.clamp(b.age / WET_APPEAR_SECONDS, 0f, 1f);
            k = 1f - (1f - k) * (1f - k); // nace creciendo
            float bx = cx + (b.offX + Mathf.sin(fxTime * 1.3f + b.phase) * wobble) * pose.scale;
            float by = cy + (b.offY + Mathf.cos(fxTime * 1.1f + b.phase * 1.7f) * wobble) * pose.scale;
            drawWetBubble(bx, by, b.sizeMul * size * k * pose.scale, pose.alpha);
        }

        // Gotas de agua (salen de vez en cuando)
        int dropTarget = Mathf.clamp(Math.round(p.textWidth / (size * 0.5f)), 2, 7);
        boolean canSpawn = remaining >= WETDROP_LIFE_MIN * 0.5f;

        for (int i = p.sparks.size - 1; i >= 0; i--) {
            Particle dp = p.sparks.get(i);
            dp.age += dt;
            if (dp.age < dp.life) continue;

            if (canSpawn && i < dropTarget && liveParticles <= particleCap) {
                spawnWaterDrop(p, dp, i, dropTarget, remaining, WETDROP_DELAY_MAX);
            } else {
                p.sparks.remove(i);
                liveParticles--;
            }
        }
        while (canSpawn && p.sparks.size < dropTarget && liveParticles < particleCap) {
            Particle dp = new Particle();
            spawnWaterDrop(p, dp, p.sparks.size, dropTarget, remaining, WETDROP_DELAY_MAX * 1.5f);
            p.sparks.add(dp);
            liveParticles++;
        }

        for (int i = 0; i < p.sparks.size; i++) {
            Particle dp = p.sparks.get(i);
            if (!computeDrop(dp, cy, pose, size, sizeFactor, WETDROP_GROW, WETDROP_SIZE, WETDROP_FALL, WETDROP_ALPHA)) continue;
            drawWaterDrop(cx + dp.offX * pose.scale, dropPose[0], dropPose[1], dropPose[2], dropPose[3]);
        }
        Draw.reset();
    }

    // Una gota de agua: color de las burbujas, con colita y un punto de luz.
    private void drawWaterDrop(float x, float y, float r, float stretch, float alpha) {
        Draw.color(WET_BUBBLE, alpha);
        Fill.circle(x, y, r);
        Fill.tri(x - r * 0.8f, y + r * 0.3f, x + r * 0.8f, y + r * 0.3f, x, y + r * (1.4f + stretch));
        Draw.color(Color.white, alpha * 0.8f);
        Fill.circle(x - r * 0.3f, y + r * 0.25f, r * 0.28f);
    }

    // Coloca una burbuja en su punto sobre (o apenas fuera de) las letras; nace tras un breve retraso.
    private void spawnWetBubble(Popup p, Particle b, int slot, int slots, float size) {
        int n = Math.max(slots, 1);
        float halfW = p.textWidth / 2f + size * 0.1f;
        float halfH = p.textHeight / 2f + size * 0.12f;
        float slotW = halfW * 2f / n;

        b.offX = -halfW + ((slot % n) + Mathf.random()) * slotW;
        b.offY = Mathf.random(-halfH, halfH);
        b.sizeMul = Mathf.random(WET_BUBBLE_MIN, WET_BUBBLE_MAX);
        b.phase = Mathf.random(Mathf.PI2);
        b.life = Float.MAX_VALUE; // dura lo que el texto
        b.age = -Mathf.random(WET_APPEAR_MAX_DELAY);
    }

    // Coloca una gota de agua en la parte de abajo del texto; nace tras una pausa al azar.
    private void spawnWaterDrop(Popup p, Particle dp, int slot, int slots, float remaining, float maxDelay) {
        int n = Math.max(slots, 1);
        float halfW = p.textWidth / 2f;
        float slotW = halfW * 2f / n;

        dp.offX = -halfW + ((slot % n) + Mathf.random()) * slotW;
        dp.offY = Mathf.random(-p.textHeight * 0.45f, 0f);
        dp.sizeMul = Mathf.random(0.8f, 1.25f);
        dp.phase = Mathf.random(Mathf.PI2);
        dp.life = Math.max(Math.min(Mathf.random(WETDROP_LIFE_MIN, WETDROP_LIFE_MAX), remaining), 0.05f);
        dp.age = -Mathf.random(maxDelay);
    }

    // Cuando el texto de Wet se acaba, entre 1 y 3 burbujas se sueltan: se quedan donde estaban,
    // se mueven apenas y desaparecen de golpe a los 0.3 a 0.6 s. El resto se va con el texto.
    private void detachWetBubbles(Popup p) {
        if (p.particles == null || p.particles.isEmpty()) return;

        computePose(p, detachPose);
        float sc = detachPose.scale < 0.05f ? 1f : detachPose.scale;
        float cx = p.anchorX + detachPose.dx + p.textDx * sc;
        float cy = p.anchorY + detachPose.dy;
        float sizeFactor = p.fontSize / 2f * particleSizeMul;
        float size = PARTICLE_BASE_SIZE * sizeFactor;
        float wobble = WET_WOBBLE * sizeFactor;

        Seq<Particle> visible = p.particles.select(b -> b.age > 0f);
        int count = Math.min(Mathf.random(WET_DETACH_MIN, WET_DETACH_MAX), visible.size);
        for (int n = 0; n < count; n++) {
            Particle b = visible.remove(Mathf.random(visible.size - 1));

            Particle o = new Particle();
            o.offX = cx + (b.offX + Mathf.sin(fxTime * 1.3f + b.phase) * wobble) * sc;
            o.offY = cy + (b.offY + Mathf.cos(fxTime * 1.1f + b.phase * 1.7f) * wobble) * sc;
            o.sizeMul = b.sizeMul * size * sc; // aqui es el radio final, en unidades de mundo
            o.phase = b.phase;
            o.age = 0f;
            o.life = Mathf.random(WET_DETACH_LIFE_MIN, WET_DETACH_LIFE_MAX);
            orphanBubbles.add(o);
        }
    }

    // ---------------------------------------------------------------------
    //  Tarred
    // ---------------------------------------------------------------------

    // Texto de Tarred: oscuro, dibujado letra por letra. Cada letra se desplaza con una velocidad
    // ligeramente distinta, asi que con el tiempo se van separando un poco.
    private void drawTarredText(Popup p, float cx, float cy, Pose pose) {
        ensureCharLayout(p);
        if (p.charStrings.length == 0) return;

        Font font = Fonts.outline;
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.25f * p.fontSize * pose.scale / Scl.scl(1f));

        drawColor.set(TEXT_TAR);
        drawColor.a *= pose.alpha;
        font.setColor(drawColor);

        float left = cx - p.textWidth * pose.scale / 2f;
        float top = cy + p.textHeight * pose.scale / 2f;
        for (int i = 0; i < p.charStrings.length; i++) {
            float extra = p.charSpeed[i] - 1f; // diferencia de esta letra con el desplazamiento comun
            font.draw(p.charStrings[i], left + p.charX[i] * pose.scale + pose.dx * extra,
                    top + pose.dy * extra, 0f, Align.left, false);
        }

        font.getData().setScale(1f);
        font.setColor(Color.white);
        font.setUseIntegerPositions(ints);
    }

    // Tarred: gotas de brea muy espaciadas (en sparks). Se forman, caen lento hasta un suelo
    // ficticio bajo el texto y ahi se aplastan en un ovalo que se ensancha y se desvanece.
    private void updateTar(Popup p, float dt, float cx, float cy, Pose pose) {
        if (p.sparks == null) p.sparks = new Seq<>();

        float sizeFactor = p.fontSize / 2f * particleSizeMul;
        float size = PARTICLE_BASE_SIZE * sizeFactor;
        float remaining = p.lifetime - p.age;
        boolean canSpawn = remaining >= TAR_LIFE_MIN * 0.5f;
        int target = Mathf.clamp(Math.round(p.textWidth / (size * 1.1f)), 2, 5);

        for (int i = p.sparks.size - 1; i >= 0; i--) {
            Particle dp = p.sparks.get(i);
            dp.age += dt;
            if (dp.age < dp.life) continue;

            if (canSpawn && i < target && liveParticles <= particleCap) {
                spawnTarDrop(p, dp, i, target, remaining, TAR_DELAY_MAX);
            } else {
                p.sparks.remove(i);
                liveParticles--;
            }
        }
        while (canSpawn && p.sparks.size < target && liveParticles < particleCap) {
            Particle dp = new Particle();
            spawnTarDrop(p, dp, p.sparks.size, target, remaining, TAR_DELAY_MAX * 1.2f);
            p.sparks.add(dp);
            liveParticles++;
        }

        float yGround = cy + (-p.textHeight / 2f - TAR_GROUND * sizeFactor) * pose.scale;
        for (int i = 0; i < p.sparks.size; i++) {
            Particle dp = p.sparks.get(i);
            if (dp.age < 0f) continue;

            float t = Mathf.clamp(dp.age / dp.life, 0f, 1f);
            float r0 = TAR_DROP_SIZE * size * dp.sizeMul * pose.scale;
            float x = cx + dp.offX * pose.scale;
            float yStart = cy + dp.offY * pose.scale;
            float a = pose.alpha;

            if (t < TAR_FORM) {
                // Se forma colgando del texto
                float k = t / TAR_FORM;
                k = k * k * (3f - 2f * k);
                drawTarDrop(x, yStart, r0 * k, 0.1f + 0.3f * k, a);
            } else if (t < TAR_FORM + TAR_FALL) {
                // Cae despacio (algo mas rapido al final) hasta el suelo
                float f = (t - TAR_FORM) / TAR_FALL;
                float y = yStart + (yGround - yStart) * (float) Math.pow(f, 1.5);
                drawTarDrop(x, y, r0, 0.4f + 0.8f * f, a);
            } else {
                // Se aplasta contra el suelo: ovalo que se ensancha y se desvanece
                float s = (t - TAR_FORM - TAR_FALL) / (1f - TAR_FORM - TAR_FALL);
                float ease = 1f - (1f - s) * (1f - s);
                float rx = r0 * (1f + TAR_SPLAT_GROW * ease);
                float fade = 1f - Math.max(0f, (s - 0.4f) / 0.6f);
                Draw.color(TEXT_TAR, a * fade);
                fillEllipse(x, yGround, rx, rx * TAR_SPLAT_FLAT);
            }
        }
        Draw.reset();
    }

    // Una gota de brea: circulo con colita hacia arriba y un brillo apagado.
    private void drawTarDrop(float x, float y, float r, float stretch, float alpha) {
        if (r < 0.05f) return;
        Draw.color(TEXT_TAR, alpha);
        Fill.circle(x, y, r);
        Fill.tri(x - r * 0.8f, y + r * 0.3f, x + r * 0.8f, y + r * 0.3f, x, y + r * (1.4f + stretch));
        Draw.color(Color.white, alpha * 0.25f);
        Fill.circle(x - r * 0.3f, y + r * 0.25f, r * 0.25f);
    }

    // Ovalo relleno con el color actual (abanico de triangulos).
    private void fillEllipse(float x, float y, float rx, float ry) {
        for (int i = 0; i < TAR_SEG; i++) {
            int j = (i + 1) % TAR_SEG;
            Fill.tri(x, y, x + TAR_COS[i] * rx, y + TAR_SIN[i] * ry, x + TAR_COS[j] * rx, y + TAR_SIN[j] * ry);
        }
    }

    // Coloca una gota de brea en la parte de abajo de las letras; nace tras una pausa larga al azar.
    private void spawnTarDrop(Popup p, Particle dp, int slot, int slots, float remaining, float maxDelay) {
        int n = Math.max(slots, 1);
        float halfW = p.textWidth / 2f;
        float slotW = halfW * 2f / n;

        dp.offX = -halfW + ((slot % n) + Mathf.random()) * slotW;
        dp.offY = -p.textHeight * 0.35f;
        dp.sizeMul = Mathf.random(0.8f, 1.2f);
        dp.phase = Mathf.random(Mathf.PI2);
        dp.life = Math.max(Math.min(Mathf.random(TAR_LIFE_MIN, TAR_LIFE_MAX), remaining), 0.05f);
        dp.age = -Mathf.random(maxDelay);
    }

    // Mide donde empieza cada letra del texto (a escala 1) para poder dibujarlas una a una.
    private void ensureCharLayout(Popup p) {
        if (p.charStrings != null) return;

        Font font = Fonts.outline;
        GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);

        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);
        font.getData().setScale(0.25f * p.fontSize / Scl.scl(1f));

        int n = p.text.length();
        p.charStrings = new String[n];
        p.charX = new float[n];
        p.charSpeed = new float[n];
        for (int i = 0; i < n; i++) {
            p.charStrings[i] = String.valueOf(p.text.charAt(i));

            // Inicio de la letra = ancho hasta ella (incluida) menos su propio ancho
            layout.setText(font, p.text.substring(0, i + 1));
            float upToHere = layout.width;
            layout.setText(font, p.charStrings[i]);
            p.charX[i] = Math.max(upToHere - layout.width, 0f);

            // Velocidad propia de la letra: ligeramente distinta, siempre la misma para este texto
            float h = (float) Math.sin(p.fxSeed * 3.7f + i * 12.9898f) * 43758.5453f;
            h -= (float) Math.floor(h);
            p.charSpeed[i] = 1f + (h - 0.5f) * TAR_SPEED_VARIATION;
        }

        Pools.free(layout);
        font.getData().setScale(1f);
        font.setUseIntegerPositions(ints);
    }

    // Color del texto derretido: rojo -> naranja -> amarillo claro, t de 0 a 1.
    private void meltingPalette(float t, Color out) {
        if (t < 0.5f) out.set(MELT_RED).lerp(MELT_ORANGE, t * 2f);
        else out.set(MELT_ORANGE).lerp(MELT_YELLOW, (t - 0.5f) * 2f);
    }

    // Texto de Melting: glow naranja/amarillo que da vueltas detras, y cada letra con su color
    // cambiante, cayendo y estirandose un poco como si se derritiera.
    private void drawMeltingText(Popup p, float cx, float cy, float scale, float alpha) {
        ensureCharLayout(p);
        if (p.charStrings.length == 0) return;

        Font font = Fonts.outline;
        boolean ints = font.usesIntegerPositions();
        font.setUseIntegerPositions(false);

        float fs = 0.25f * p.fontSize * scale / Scl.scl(1f);
        float sizeFactor = p.fontSize / 2f * scale;
        float left = cx - p.textWidth * scale / 2f;
        float top = cy + p.textHeight * scale / 2f;

        float orbit = fxTime * MELT_GLOW_SPEED + p.fxSeed;
        float ox = Mathf.cos(orbit) * MELT_GLOW_OFFSET * sizeFactor;
        float oy = Mathf.sin(orbit) * MELT_GLOW_OFFSET * sizeFactor;

        // Glow (suma luz; el contorno negro del texto no se nota al sumarse)
        Draw.blend(Blending.additive);
        drawMeltingChars(p, fs, scale, left, top, sizeFactor, ox, oy, MELT_GLOW_ORANGE, alpha);
        drawMeltingChars(p, fs, scale, left, top, sizeFactor, -ox, -oy, MELT_GLOW_YELLOW, alpha);
        Draw.blend();

        // Texto
        drawMeltingChars(p, fs, scale, left, top, sizeFactor, 0f, 0f, null, alpha);

        font.getData().setScale(1f);
        font.setColor(Color.white);
        font.setUseIntegerPositions(ints);
    }

    // Dibuja las letras una a una. tint == null usa la paleta de Melting.
    private void drawMeltingChars(Popup p, float fs, float scale, float left, float top, float sizeFactor,
                                  float offX, float offY, Color tint, float alpha) {
        Font font = Fonts.outline;
        int n = p.charStrings.length;

        for (int i = 0; i < n; i++) {
            float d = 0.5f + 0.5f * Mathf.sin(fxTime * MELT_WOBBLE_SPEED + i * 1.3f + p.fxSeed); // 0..1
            font.getData().setScale(fs * (1f - 0.05f * d), fs * (1f + 0.12f * d));

            if (tint != null) {
                drawColor.set(tint);
            } else {
                float t = 0.5f + 0.5f * Mathf.sin(fxTime * MELT_COLOR_SPEED + i * 0.8f + p.fxSeed);
                meltingPalette(t, drawColor);
            }
            drawColor.a *= alpha;
            font.setColor(drawColor);

            font.draw(p.charStrings[i], left + p.charX[i] * scale + offX,
                    top - d * MELT_DROOP * sizeFactor + offY, 0f, Align.left, false);
        }
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
