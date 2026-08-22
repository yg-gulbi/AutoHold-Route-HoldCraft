# Climbing Hold Instance Segmentation

YOLO11 instance segmentation experiment for climbing holds.

## Training

Install the Python dependencies used by the pipeline:

```powershell
pip install ultralytics opencv-python
```

From `01.climbing_instanceseg/`, run:

```powershell
python yolo_utiles.py
```

The dataset details and experiment-specific settings are documented in the README inside each `runs/` experiment folder.

The current pipeline uses:

- Model: `yolo11m-seg.pt`
- Epochs: 100
- Image size: 640
- Batch size: 16
- Device: GPU `0` (change to `cpu` if needed)
- Early stopping patience: 20
- Resume: disabled

Training outputs are written to the experiment directory configured by `exp_name`.