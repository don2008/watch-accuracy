"""Train and export a tiny offline watch-layout classifier.

Input images are converted to a 32x32 luminance grid.  The exported JSON uses
the same dense ReLU/softmax format that can be evaluated without ML libraries
on Android.  This is the layout stage; hand/OCR readers run afterwards.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageEnhance
from sklearn.metrics import confusion_matrix
from sklearn.model_selection import train_test_split
from sklearn.neural_network import MLPClassifier
from sklearn.preprocessing import StandardScaler


LAYOUTS = ("classic", "small_seconds", "regulator", "jump_hour")
GRID = 32


def features(path: Path) -> np.ndarray:
    return image_features(Image.open(path))


def image_features(source: Image.Image) -> np.ndarray:
    image = source.convert("L").resize((GRID, GRID), Image.Resampling.BILINEAR)
    values = np.asarray(image, dtype=np.float32) / 255.0
    # Normalise each photograph so brightness changes do not dominate layout.
    values = (values - values.mean()) / max(float(values.std()), .08)
    return values.reshape(-1)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--real-root", type=Path)
    parser.add_argument("--real-augmentations", type=int, default=60)
    parser.add_argument("--seed", type=int, default=20260915)
    args = parser.parse_args()

    paths: list[Path] = []
    labels: list[int] = []
    for label, layout in enumerate(LAYOUTS):
        found = sorted((args.dataset / layout).glob("*.jpg"))
        if not found:
            raise SystemExit(f"No images found for {layout}")
        paths.extend(found)
        labels.extend([label] * len(found))
    x = np.stack([features(path) for path in paths])
    y = np.asarray(labels)
    xt, xv, yt, yv = train_test_split(
        x, y, test_size=.20, random_state=args.seed, stratify=y)

    # Fine-tune against the photographic domain. Keep the last alphabetic
    # original from each class completely unseen as a small reality check.
    real_validation_x: list[np.ndarray] = []
    real_validation_y: list[int] = []
    if args.real_root:
        rng = np.random.default_rng(args.seed)
        extra_x: list[np.ndarray] = []
        extra_y: list[int] = []
        for label, layout in enumerate(LAYOUTS):
            candidates = sorted((args.real_root / layout / "original").glob("*"))
            candidates = [p for p in candidates if p.is_file()]
            if not candidates:
                continue
            held_out, training = candidates[-1], candidates[:-1]
            real_validation_x.append(features(held_out))
            real_validation_y.append(label)
            for path in training:
                original = Image.open(path).convert("RGB")
                for _ in range(args.real_augmentations):
                    sample = original.rotate(float(rng.uniform(-12, 12)),
                                             resample=Image.Resampling.BICUBIC,
                                             fillcolor=original.getpixel((0, 0)))
                    sample = ImageEnhance.Brightness(sample).enhance(float(rng.uniform(.75, 1.22)))
                    sample = ImageEnhance.Contrast(sample).enhance(float(rng.uniform(.78, 1.28)))
                    extra_x.append(image_features(sample))
                    extra_y.append(label)
        if extra_x:
            xt = np.concatenate((xt, np.stack(extra_x)))
            yt = np.concatenate((yt, np.asarray(extra_y)))
    scaler = StandardScaler().fit(xt)
    xt, xv = scaler.transform(xt), scaler.transform(xv)
    model = MLPClassifier(
        hidden_layer_sizes=(32,), activation="relu", solver="adam",
        batch_size=128, learning_rate_init=.0015, max_iter=80,
        early_stopping=True, validation_fraction=.12, n_iter_no_change=8,
        random_state=args.seed,
    ).fit(xt, yt)
    prediction = model.predict(xv)
    accuracy = float(np.mean(prediction == yv))
    matrix = confusion_matrix(yv, prediction, labels=range(len(LAYOUTS)))
    real_accuracy = None
    real_predictions: list[int] = []
    if real_validation_x:
        real_predictions = model.predict(scaler.transform(np.stack(real_validation_x))).tolist()
        real_accuracy = float(np.mean(np.asarray(real_predictions) == np.asarray(real_validation_y)))
        print(f"held-out real accuracy={real_accuracy:.4f}; predictions={real_predictions}")
    payload = {
        "version": 1,
        "grid": GRID,
        "layouts": list(LAYOUTS),
        "mean": scaler.mean_.round(5).tolist(),
        "std": scaler.scale_.round(5).tolist(),
        "w1": model.coefs_[0].round(5).tolist(),
        "b1": model.intercepts_[0].round(5).tolist(),
        "w2": model.coefs_[1].round(5).tolist(),
        "b2": model.intercepts_[1].round(5).tolist(),
        "validation_accuracy_synthetic": round(accuracy, 6),
        "validation_accuracy_real_small_holdout": None if real_accuracy is None else round(real_accuracy, 6),
        "confusion_matrix": matrix.tolist(),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, separators=(",", ":")), encoding="utf-8")
    print(f"validation accuracy={accuracy:.4f}; iterations={model.n_iter_}")
    print(matrix)
    print(f"saved {args.output} ({args.output.stat().st_size / 1024:.1f} KiB)")


if __name__ == "__main__":
    main()
