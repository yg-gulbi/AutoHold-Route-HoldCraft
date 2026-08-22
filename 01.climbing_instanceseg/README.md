# 클라이밍 홀드 인스턴스 세그멘테이션

이 폴더는 클라이밍 암장 이미지에서 `hold`와 `volume`을 검출하고, 각 객체의 세그멘테이션 마스크까지 추출하는 YOLO11 실험 코드와 결과를 담고 있습니다.

## 핵심 내용

- 모델: `YOLO11m-seg`
- 작업: instance segmentation
- 클래스: `hold`, `volume`
- 메인 스크립트: `yolo_utiles.py`
- 빠른 추론 테스트: `test.py`
- 최신 실험 결과: `runs/yolo11_climbing_seg_vr_1.1`

## 폴더 구성

```text
01.climbing_instanceseg/
├── README.md
├── yolo_utiles.py
├── test.py
├── dataset/
├── test/
├── notebooks/archive/
└── runs/
    ├── yolo11_climbing_seg_vr_1.0/
    └── yolo11_climbing_seg_vr_1.1/
```

- `yolo_utiles.py`: 학습, 검증, 추론, JSON 저장을 담당하는 파이프라인입니다.
- `test.py`: 하드코딩된 모델과 이미지 경로로 추론 결과 이미지를 빠르게 생성합니다.
- `dataset/`: 노트북과 파이프라인이 실제 학습 때 읽는 데이터셋 위치입니다. 로컬에 직접 준비해야 하며 Git에는 포함하지 않습니다.
- `test/`: 개인 이미지 테스트 샘플과 예측 결과가 들어 있습니다.
- `runs/`: 실험별 학습 로그, 모델 가중치, 검증 지표, 예측 이미지가 저장됩니다.

## 설치

```powershell
pip install ultralytics opencv-python
```

GPU를 사용하지 않는 환경에서는 스크립트의 `device` 값을 `cpu`로 변경하세요.

## 데이터셋 준비

`yolo_utiles.ipynb`는 `yolo_utiles.py`에서 `DATASET_DIR`와 `DATA_YAML`을 가져와 사용합니다. 따라서 학습 데이터셋은 기본적으로 아래 경로에 있어야 합니다.

```text
01.climbing_instanceseg/dataset/data.yaml
```

현재 `dataset/` 폴더는 `.gitignore`에 포함되어 있으므로, 학습을 다시 실행하려면 Roboflow 데이터셋을 직접 내려받아 위 경로에 배치해야 합니다. 노트북의 Roboflow 다운로드 셀도 `location=str(DATASET_DIR)`를 사용하므로 다운로드 위치 역시 이 `dataset/` 폴더가 맞습니다.

`runs/yolo11_climbing_seg_vr_1.1/data_info/`에는 최신 실험에 남겨진 데이터셋 설정과 Roboflow 메타데이터가 들어 있습니다. 이 폴더는 학습 입력 원본이 아니라 실험 기록용입니다.

최신 실험 데이터셋 정보:

- 출처: Roboflow `Climbing Hold Detection` v29
- 라이선스: CC BY 4.0
- 전체 이미지 수: 4,931장
- 클래스: `hold`, `volume`
- 형식: YOLOv11 segmentation

## 학습 실행

`01.climbing_instanceseg/` 폴더에서 실행합니다.

```powershell
python yolo_utiles.py
```

실행하면 모델 가중치 경로와 추론할 이미지 또는 폴더 경로를 입력받습니다. 이후 `train`, `val`, `predict` 순서로 파이프라인이 실행됩니다.

현재 기본 학습 설정:

| 항목 | 값 |
| --- | --- |
| Epochs | 100 |
| 이미지 크기 | 640 |
| Batch size | 16 |
| Device | GPU `0` |
| Early stopping patience | 20 |
| Resume | 사용 안 함 |

학습 결과는 `YOLOSegmentPipeline`을 만들 때 지정한 `exp_name`에 따라 `runs/` 아래 실험 폴더에 저장됩니다.

## 빠른 추론 테스트

학습 없이 저장된 모델로 개인 이미지 추론만 실행하려면 `test.py`를 사용합니다.

```powershell
python test.py
```

현재 `test.py`의 `model_path`와 `source_path`는 로컬 절대 경로로 하드코딩되어 있습니다. 다른 모델이나 이미지로 테스트하려면 해당 값을 먼저 수정해야 합니다. 결과 이미지는 입력 이미지와 같은 폴더에 `_pred_...jpg` 형식으로 저장됩니다.

## 실험 결과

최신 실험인 `yolo11_climbing_seg_vr_1.1`은 `vr_1.0` 대비 모든 주요 지표가 개선되었습니다.

| 지표 | vr_1.0 | vr_1.1 | 변화 |
| --- | ---: | ---: | ---: |
| Box mAP50-95 | 0.6976 | 0.7736 | +0.0760 |
| Box mAP50 | 0.8568 | 0.9202 | +0.0634 |
| Mask mAP50-95 | 0.5504 | 0.5840 | +0.0336 |
| Mask mAP50 | 0.8149 | 0.8652 | +0.0503 |
| Precision | 0.8834 | 0.9047 | +0.0213 |
| Recall | 0.8081 | 0.8751 | +0.0670 |

상세 검증 결과는 아래 파일에서 확인할 수 있습니다.

```text
runs/yolo11_climbing_seg_vr_1.1/val/val_metrics.json
```

## 주요 산출물

- `train/weights/best.pt`: 가장 성능이 좋은 모델 가중치
- `train/weights/last.pt`: 마지막 학습 epoch의 모델 가중치
- `train/results.csv`: 학습 과정의 수치 로그
- `train/results.png`: 학습 지표 그래프
- `val/val_metrics.json`: 검증 성능 요약
- `predict/inference_results.json`: 추론 결과 JSON
- `predict/*_pred.jpg`: 예측 결과가 시각화된 이미지

`inference_results.json`에는 이미지별 검출 객체, 클래스, confidence, bounding box, mask polygon 정보가 저장됩니다.
