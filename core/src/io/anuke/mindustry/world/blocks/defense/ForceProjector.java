package io.anuke.mindustry.world.blocks.defense;

import io.anuke.arc.Core;
import io.anuke.mindustry.entities.Effects;
import io.anuke.mindustry.entities.EntityGroup;
import io.anuke.mindustry.entities.EntityQuery;
import io.anuke.mindustry.entities.impl.BaseEntity;
import io.anuke.mindustry.entities.traits.DrawTrait;
import io.anuke.arc.graphics.Blending;
import io.anuke.arc.graphics.Color;
import io.anuke.arc.graphics.g2d.Draw;
import io.anuke.arc.graphics.g2d.Fill;
import io.anuke.arc.graphics.g2d.TextureRegion;
import io.anuke.arc.math.Mathf;
import io.anuke.arc.util.Time;
import io.anuke.mindustry.content.Fx;
import io.anuke.mindustry.entities.type.TileEntity;
import io.anuke.mindustry.entities.traits.AbsorbTrait;
import io.anuke.mindustry.graphics.Pal;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.Tile;
import io.anuke.mindustry.world.consumers.ConsumeLiquidFilter;
import io.anuke.mindustry.world.consumers.ConsumePower;
import io.anuke.mindustry.world.meta.BlockStat;
import io.anuke.mindustry.world.meta.StatUnit;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import static io.anuke.mindustry.Vars.*;

public class ForceProjector extends Block {
    protected int timerUse = timers ++;
    protected float phaseUseTime = 350f;

    protected float phaseRadiusBoost = 80f;
    protected float radius = 100f;
    protected float breakage = 550f;
    protected float cooldownNormal = 1.75f;
    protected float cooldownLiquid = 1.5f;
    protected float cooldownBrokenBase = 0.35f;
    protected float basePowerDraw = 0.2f;
    protected float powerDamage = 0.1f;
    protected final ConsumeForceProjectorPower consumePower;
    protected TextureRegion topRegion;


    public ForceProjector(String name) {
        super(name);
        update = true;
        solid = true;
        hasPower = true;
        canOverdrive = false;
        hasLiquids = true;
        hasItems = true;
        consumes.add(new ConsumeLiquidFilter(liquid -> liquid.temperature <= 0.5f && liquid.flammability < 0.1f, 0.1f)).optional(true).boost(true).update(false);
        consumePower = new ConsumeForceProjectorPower(60f, 60f);
        consumes.add(consumePower);
    }

    @Override
    public void load(){
        super.load();
        topRegion = Core.atlas.find(name + "-top");
    }

    @Override
    public void setStats(){
        super.setStats();

        stats.add(BlockStat.powerUse, basePowerDraw * 60f, StatUnit.powerSecond);
        stats.add(BlockStat.powerDamage, powerDamage, StatUnit.powerUnits);
    }

    @Override
    public void update(Tile tile){
        ForceEntity entity = tile.entity();
        boolean cheat = tile.isEnemyCheat();

        if(entity.shield == null){
            entity.shield = new ShieldEntity(tile);
            entity.shield.add();
        }

        entity.phaseHeat = Mathf.lerpDelta(entity.phaseHeat, (float)entity.items.get(consumes.item()) / itemCapacity, 0.1f);

        if(entity.cons.valid() && !entity.broken && entity.timer.get(timerUse, phaseUseTime) && entity.items.total() > 0){
            entity.items.remove(consumes.item(), 1);
        }

        entity.radscl = Mathf.lerpDelta(entity.radscl, entity.broken ? 0f : 1f, 0.05f);

        if(Mathf.chance(Time.delta() * entity.buildup / breakage * 0.1f)){
            Effects.effect(Fx.reactorsmoke, tile.drawx() + Mathf.range(tilesize/2f), tile.drawy() + Mathf.range(tilesize/2f));
        }

        // Use Cases:
        // - There is enough power in the buffer, and there are no shots fired => Draw base power and keep shield up
        // - There is enough power in the buffer, but not enough power to cope for shots being fired => Draw all power and break shield
        // - There is enough power in the buffer and enough power to cope for shots being fired => Draw base power + additional power based on shots absorbed
        // - There is not enough base power in the buffer => Draw all power and break shield
        // - The generator is in the AI base and uses cheat mode => Only draw power from shots being absorbed

        float relativePowerDraw = 0.0f;
        if(!cheat){
            relativePowerDraw = basePowerDraw / consumePower.powerCapacity;
        }

        if(entity.power.satisfaction < relativePowerDraw){
            entity.warmup = Mathf.lerpDelta(entity.warmup, 0f, 0.15f);
            entity.power.satisfaction = .0f;
            if(entity.warmup <= 0.09f){
                entity.broken = true;
            }
        }else{
            entity.warmup = Mathf.lerpDelta(entity.warmup, 1f, 0.1f);
            entity.power.satisfaction -= Math.min(entity.power.satisfaction, relativePowerDraw);
        }

        if(entity.buildup > 0){
            float scale = !entity.broken ? cooldownNormal : cooldownBrokenBase;
            if(consumes.get(ConsumeLiquidFilter.class).valid(this, entity)){
                consumes.get(ConsumeLiquidFilter.class).update(this, entity);
                scale *= (cooldownLiquid * (1f+(entity.liquids.current().heatCapacity-0.4f)*0.9f));
            }

            entity.buildup -= Time.delta()*scale;
        }

        if(entity.broken && entity.buildup <= 0 && entity.warmup >= 0.9f){
            entity.broken = false;
        }

        if(entity.buildup >= breakage && !entity.broken){
            entity.broken = true;
            entity.buildup = breakage;
            Effects.effect(Fx.shieldBreak, tile.drawx(), tile.drawy(), radius);
        }

        if(entity.hit > 0f){
            entity.hit -= 1f/5f * Time.delta();
        }

        float realRadius = realRadius(entity);

        if(!entity.broken){
            EntityQuery.getNearby(bulletGroup, tile.drawx(), tile.drawy(), realRadius*2f, bullet -> {
                AbsorbTrait trait = (AbsorbTrait)bullet;
                if(trait.canBeAbsorbed() && trait.getTeam() != tile.getTeam() && isInsideHexagon(trait.getX(), trait.getY(), realRadius * 2f, tile.drawx(), tile.drawy())){
                    trait.absorb();
                    Effects.effect(Fx.absorb, trait);
                    float relativeDamagePowerDraw = trait.getShieldDamage() * powerDamage / consumePower.powerCapacity;
                    entity.hit = 1f;

                    entity.power.satisfaction -= Math.min(relativeDamagePowerDraw, entity.power.satisfaction);
                    if(entity.power.satisfaction <= 0.0001f){
                       entity.buildup += trait.getShieldDamage() * entity.warmup * 2f;
                    }
                    entity.buildup += trait.getShieldDamage() * entity.warmup;
                }
            });
        }
    }

    float realRadius(ForceEntity entity){
        return (radius+entity.phaseHeat*phaseRadiusBoost) * entity.radscl;
    }

    boolean isInsideHexagon(float x0, float y0, float d, float x, float y) {
        float dx = Math.abs(x - x0)/d;
        float dy = Math.abs(y - y0)/d;
        float a = 0.25f * Mathf.sqrt3;
        return (dy <= a) && (a*dx + 0.25*dy <= 0.5*a);
    }

    @Override
    public void draw(Tile tile){
        super.draw(tile);

        ForceEntity entity = tile.entity();

        if(entity.buildup <= 0f) return;
        Draw.alpha(entity.buildup / breakage * 0.75f);
        Draw.blend(Blending.additive);
        Draw.rect(topRegion, tile.drawx(), tile.drawy());
        Draw.blend();
        Draw.reset();
    }

    @Override
    public TileEntity newEntity(){
        return new ForceEntity();
    }

    class ForceEntity extends TileEntity{
        ShieldEntity shield;
        boolean broken = true;
        float buildup = 0f;
        float radscl = 0f;
        float hit;
        float warmup;
        float phaseHeat;

        @Override
        public void write(DataOutput stream) throws IOException{
            stream.writeBoolean(broken);
            stream.writeFloat(buildup);
            stream.writeFloat(radscl);
            stream.writeFloat(warmup);
            stream.writeFloat(phaseHeat);
        }

        @Override
        public void read(DataInput stream) throws IOException{
            broken = stream.readBoolean();
            buildup = stream.readFloat();
            radscl = stream.readFloat();
            warmup = stream.readFloat();
            phaseHeat = stream.readFloat();
        }
    }

    public class ShieldEntity extends BaseEntity implements DrawTrait{
        final ForceEntity entity;

        public ShieldEntity(Tile tile){
            this.entity = tile.entity();
            set(tile.drawx(), tile.drawy());
        }

        @Override
        public void update(){
            if(entity.isDead() || !entity.isAdded()){
                remove();
            }
        }

        @Override
        public float drawSize(){
            return realRadius(entity)*2f+2f;
        }

        @Override
        public void draw(){
            Draw.color(Pal.accent);
            Fill.poly(x, y, 6, realRadius(entity));
            Draw.color();
        }

        public void drawOver(){
            if(entity.hit <= 0f) return;

            Draw.color(Color.WHITE);
            Draw.alpha(entity.hit);
            Fill.poly(x, y, 6, realRadius(entity));
            Draw.color();
        }

        @Override
        public EntityGroup targetGroup(){
            return shieldGroup;
        }
    }

    public class ConsumeForceProjectorPower extends ConsumePower{
        public ConsumeForceProjectorPower(float powerCapacity, float ticksToFill){
            super(powerCapacity / ticksToFill, powerCapacity, true);
        }
        @Override
        public boolean valid(Block block, TileEntity entity){
            return entity.power.satisfaction >= basePowerDraw / powerCapacity && super.valid(block, entity);
        }
    }
}
