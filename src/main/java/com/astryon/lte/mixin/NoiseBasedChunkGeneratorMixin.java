package com.astryon.lte.mixin;

import com.astryon.lte.terrain.TerrainAnalyzer;
import com.astryon.lte.chunk.ChunkQueue;
import com.astryon.lte.chunk.CompletedChunkCache;

import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NoiseBasedChunkGenerator.class)
public class NoiseBasedChunkGeneratorMixin {

    @Inject(
        method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
        at = @At("TAIL"),
        require = 0
    )
    private void lte$afterSurfaceBuild(
            WorldGenRegion region,
            StructureManager structureManager,
            RandomState randomState,
            ChunkAccess chunk,
            CallbackInfo ci
    ) {

        int x = chunk.getPos().x();
        int z = chunk.getPos().z();


        boolean verbose =
            com.astryon.lte.config.LTEConfig.verbose;


        if (CompletedChunkCache.isCompleted(x, z)) {

            if (verbose) {

                System.out.println(
                    "[LTE] Skipping completed chunk: "
                    + x + ", " + z
                );
            }

            return;
        }


        if (verbose) {

            System.out.println(
                "[LTE] Hook after surface build: "
                + x + ", " + z
            );
        }


        TerrainAnalyzer.analyze(chunk);

    }
}
