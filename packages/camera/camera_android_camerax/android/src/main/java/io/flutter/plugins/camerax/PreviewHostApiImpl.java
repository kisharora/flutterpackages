// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camerax;

import android.graphics.SurfaceTexture;
import android.util.Size;
import android.view.Surface;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.AspectRatioStrategy;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.PreviewView;
import androidx.camera.core.ResolutionInfo;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugins.camerax.GeneratedCameraXLibrary.PreviewHostApi;
import io.flutter.plugins.camerax.GeneratedCameraXLibrary.ResolutionInfo;
import io.flutter.view.TextureRegistry;
import java.util.Objects;
import java.util.concurrent.Executors;

public class PreviewHostApiImpl implements PreviewHostApi {
  final BinaryMessenger binaryMessenger;
  private final InstanceManager instanceManager;
  private final TextureRegistry textureRegistry;

  @VisibleForTesting public @NonNull CameraXProxy cameraXProxy = new CameraXProxy();
  @VisibleForTesting public @Nullable TextureRegistry.SurfaceProducer flutterSurfaceProducer;

  public PreviewHostApiImpl(
      @NonNull BinaryMessenger binaryMessenger,
      @NonNull InstanceManager instanceManager,
      @NonNull TextureRegistry textureRegistry) {
    this.binaryMessenger = binaryMessenger;
    this.instanceManager = instanceManager;
    this.textureRegistry = textureRegistry;
  }

  /** Creates a {@link Preview} with the target rotation and resolution if specified. */
  @Override
  public void create(
      @NonNull Long identifier,
      @Nullable Long rotation,
      @Nullable ResolutionInfo targetResolution) {
    Preview.Builder previewBuilder = cameraXProxy.createPreviewBuilder();
    
    // Set consistent preview scaling mode for smooth preview like Instagram/Snapchat
    previewBuilder.setTargetRotation(Surface.ROTATION_0)
                 .setCaptureMode(Preview.CaptureMode.MINIMIZE_LATENCY)
                 .setResolutionSelector(
                     new ResolutionSelector.Builder()
                         .setAspectRatioStrategy(
                             new AspectRatioStrategy.Builder()
                                 .setAspectRatio(AspectRatio.RATIO_16_9)
                                 .setFallbackRule(AspectRatioStrategy.FALLBACK_RULE_AUTO)
                                 .build())
                         .build());

    if (rotation != null) {
      previewBuilder.setTargetRotation(rotation.intValue());
    }
    if (targetResolution != null) {
      previewBuilder.setTargetResolution(
          new Size(
              targetResolution.getWidth().intValue(),
              targetResolution.getHeight().intValue()));
    }
    
    Preview preview = previewBuilder.build();
    instanceManager.addDartCreatedInstance(preview, identifier);
  }

  /**
   * Sets the {@link Preview.SurfaceProvider} that will be used to provide a {@code Surface} backed
   * by a Flutter {@link TextureRegistry.SurfaceTextureEntry} used to build the {@link Preview}.
   */
  @Override
  public @NonNull Long setSurfaceProvider(@NonNull Long identifier) {
    Preview preview = getPreviewInstance(identifier);
    flutterSurfaceProducer = textureRegistry.createSurfaceProducer();
    SurfaceTexture surfaceTexture = flutterSurfaceProducer.getSurfaceTexture();
    Preview.SurfaceProvider surfaceProvider = createSurfaceProvider(surfaceTexture);
    preview.setSurfaceProvider(surfaceProvider);

    return flutterSurfaceProducer.id();
  }

  /**
   * Creates a {@link Preview.SurfaceProvider} that specifies how to provide a {@link Surface} to a
   * {@code Preview} that is backed by a Flutter {@link TextureRegistry.SurfaceTextureEntry}.
   */
  @VisibleForTesting
  public Preview.SurfaceProvider createSurfaceProvider(@NonNull SurfaceTexture surfaceTexture) {
    return new Preview.SurfaceProvider() {
      @Override
      public void onSurfaceRequested(@NonNull SurfaceRequest request) {
        surfaceTexture.setDefaultBufferSize(
            request.getResolution().getWidth(), request.getResolution().getHeight());
        Surface flutterSurface = cameraXProxy.createSurface(surfaceTexture);

        // Get camera info to check if it's front facing
        CameraInfo cameraInfo = request.getCamera().getCameraInfo();
        boolean isFrontFacing = cameraInfo.getLensFacing() == CameraSelector.LENS_FACING_FRONT;

        // Configure transform based on camera facing
        SurfaceRequest.TransformationInfo.Builder transformationBuilder = 
            new SurfaceRequest.TransformationInfo.Builder()
                .setTargetRotation(Surface.ROTATION_0);
        
        if (isFrontFacing) {
            transformationBuilder.setHorizontalFlip(true);  // Mirror for front camera
        }

        request.setTransformationInfo(transformationBuilder.build());

        request.provideSurface(
            flutterSurface,
            Executors.newSingleThreadExecutor(),
            (result) -> {
              flutterSurface.release();
              int resultCode = result.getResultCode();
              switch (resultCode) {
                case SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY:
                  break;
                default:
                  SystemServicesFlutterApiImpl systemServicesFlutterApi =
                      cameraXProxy.createSystemServicesFlutterApiImpl(binaryMessenger);
                  systemServicesFlutterApi.sendCameraError(
                      getProvideSurfaceErrorDescription(resultCode), reply -> {});
                  break;
              }
            });
      }
    };
  }

  /**
   * Returns an error description for each {@link SurfaceRequest.Result} that represents an error
   * with providing a surface.
   */
  String getProvideSurfaceErrorDescription(int resultCode) {
    switch (resultCode) {
      case SurfaceRequest.Result.RESULT_INVALID_SURFACE:
        return resultCode + ": Provided surface could not be used by the camera.";
      default:
        return resultCode + ": Attempt to provide a surface resulted with unrecognizable code.";
    }
  }

  /**
   * Releases the Flutter {@link TextureRegistry.SurfaceTextureEntry} if used to provide a surface
   * for a {@link Preview}.
   */
  @Override
  public void releaseFlutterSurfaceTexture() {
    if (flutterSurfaceProducer != null) {
      flutterSurfaceProducer.release();
    }
  }

  /** Returns the resolution information for the specified {@link Preview}. */
  @Override
  public @NonNull ResolutionInfo getResolutionInfo(
      @NonNull Long identifier) {
    Preview preview = getPreviewInstance(identifier);
    Size resolution = preview.getResolutionInfo().getResolution();

    ResolutionInfo.Builder resolutionInfo =
        new ResolutionInfo.Builder()
            .setWidth(Long.valueOf(resolution.getWidth()))
            .setHeight(Long.valueOf(resolution.getHeight()));
    return resolutionInfo.build();
  }

  /** Dynamically sets the target rotation of the {@link Preview}. */
  @Override
  public void setTargetRotation(@NonNull Long identifier, @NonNull Long rotation) {
    Preview preview = getPreviewInstance(identifier);
    preview.setTargetRotation(rotation.intValue());
  }

  /** Retrieves the {@link Preview} instance associated with the specified {@code identifier}. */
  private Preview getPreviewInstance(@NonNull Long identifier) {
    return Objects.requireNonNull(instanceManager.getInstance(identifier));
  }
}
