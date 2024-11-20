// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.camerax;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.AspectRatioStrategy;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ResolutionSelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.VideoCapture;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugins.camerax.GeneratedCameraXLibrary.VideoCaptureHostApi;
import io.flutter.plugins.camerax.GeneratedCameraXLibrary.QualitySelectorHostApi;
import java.util.Objects;

public class VideoCaptureHostApiImpl implements VideoCaptureHostApi {
  private final BinaryMessenger binaryMessenger;
  private final InstanceManager instanceManager;
  private final CameraXProxy cameraXProxy;

  public VideoCaptureHostApiImpl(
      @NonNull BinaryMessenger binaryMessenger, 
      @NonNull InstanceManager instanceManager, 
      @NonNull CameraXProxy cameraXProxy) {
    this.binaryMessenger = binaryMessenger;
    this.instanceManager = instanceManager;
    this.cameraXProxy = cameraXProxy;
  }

  @Override
  @NonNull
  public Long withOutput(@NonNull Long videoOutputId) {
    Recorder recorder =
        (Recorder) Objects.requireNonNull(instanceManager.getInstance(videoOutputId));
    VideoCapture<Recorder> videoCapture = VideoCapture.withOutput(recorder);
    final VideoCaptureFlutterApiImpl videoCaptureFlutterApi =
        getVideoCaptureFlutterApiImpl(binaryMessenger, instanceManager);
    videoCaptureFlutterApi.create(videoCapture, result -> {});
    return Objects.requireNonNull(instanceManager.getIdentifierForStrongReference(videoCapture));
  }

  @Override
  @NonNull
  public Long getOutput(@NonNull Long identifier) {
    VideoCapture<Recorder> videoCapture = getVideoCaptureInstance(identifier);
    Recorder recorder = videoCapture.getOutput();
    return Objects.requireNonNull(instanceManager.getIdentifierForStrongReference(recorder));
  }

  @VisibleForTesting
  @NonNull
  public VideoCaptureFlutterApiImpl getVideoCaptureFlutterApiImpl(
      @NonNull BinaryMessenger binaryMessenger, @NonNull InstanceManager instanceManager) {
    return new VideoCaptureFlutterApiImpl(binaryMessenger, instanceManager);
  }

  /** Dynamically sets the target rotation of the {@link VideoCapture}. */
  @Override
  public void setTargetRotation(@NonNull Long identifier, @NonNull Long rotation) {
    VideoCapture<Recorder> videoCapture = getVideoCaptureInstance(identifier);
    videoCapture.setTargetRotation(rotation.intValue());
  }

  /**
   * Retrieves the {@link VideoCapture} instance associated with the specified {@code identifier}.
   */
  private VideoCapture<Recorder> getVideoCaptureInstance(@NonNull Long identifier) {
    return Objects.requireNonNull(instanceManager.getInstance(identifier));
  }

  /** Creates a {@link VideoCapture} with the target rotation and quality selector if specified. */
  @Override
  public void create(
      @NonNull Long identifier,
      @Nullable Long rotation,
      @Nullable Long qualitySelectorId) {
    VideoCapture.Builder videoCaptureBuilder = cameraXProxy.createVideoCaptureBuilder();

    // Match video settings with preview for consistent output
    videoCaptureBuilder
        .setTargetRotation(Surface.ROTATION_0)
        .setResolutionSelector(
            new ResolutionSelector.Builder()
                .setAspectRatioStrategy(
                    new AspectRatioStrategy.Builder()
                        .setAspectRatio(AspectRatio.RATIO_16_9)
                        .setFallbackRule(AspectRatioStrategy.FALLBACK_RULE_AUTO)
                        .build())
                .build());

    if (rotation != null) {
      videoCaptureBuilder.setTargetRotation(rotation.intValue());
    }
    if (qualitySelectorId != null) {
      QualitySelector qualitySelector =
          Objects.requireNonNull(instanceManager.getInstance(qualitySelectorId));
      videoCaptureBuilder.setQualitySelector(qualitySelector);
    }

    VideoCapture<Recorder> videoCapture = videoCaptureBuilder.build();
    
    // Configure output transform for front camera mirroring
    CameraInfo cameraInfo = videoCapture.getCamera().getCameraInfo();
    if (cameraInfo.getLensFacing() == CameraSelector.LENS_FACING_FRONT) {
      videoCapture.setTransformationInfo(
          new VideoCapture.TransformationInfo.Builder()
              .setTargetRotation(Surface.ROTATION_0)
              .setHorizontalFlip(true)  // Mirror for front camera
              .build());
    }

    instanceManager.addDartCreatedInstance(videoCapture, identifier);
  }
}
