import os
import json
import cv2
from ultralytics import YOLO


# 현재 Python 파일이 있는 폴더를 프로젝트 기준 경로로 사용합니다.
PROJECT_DIR = os.path.dirname(os.path.abspath(__file__)) 
DATASET_DIR = os.path.join(PROJECT_DIR, "dataset")
DATA_YAML = os.path.join(DATASET_DIR, "data.yaml")
RUNS_DIR = os.path.join(PROJECT_DIR, "runs")


class YOLOSegmentPipeline:

    def __init__(self, model_path: str, base_dir: str = RUNS_DIR, exp_name: str = "yolo11_climbing_seg"):
        self.model_path = model_path
        self.model = YOLO(model_path)
        
        self.base_dir = base_dir
        self.exp_name = exp_name
        
        # os.path.join(): OS(Linux/Windows) 환경에 맞춰 경로 구분자(/ 또는 \)를 자동 결합
        self.exp_dir = os.path.join(self.base_dir, self.exp_name)
        
        self.train_dir = os.path.join(self.exp_dir, "train")
        self.val_dir = os.path.join(self.exp_dir, "val")
        self.predict_dir = os.path.join(self.exp_dir, "predict")

    def train(self, data_yaml: str
              , epochs: int = 100
              , imgsz: int = 640
              , batch: int = 16
              , device: str | int = 0
              , patience: int = 20
              , resume: bool = False) : 
        print(f"\n--- [Train] 모델 학습 시작 (출력 경로: {self.train_dir}) ---")
        
        results = self.model.train(
              data=data_yaml
            , epochs=epochs
            , imgsz=imgsz
            , batch=batch
            , project=os.path.abspath(self.exp_dir)
            , name="train"
            , exist_ok=True
            , device=device
            , patience=patience
            , save=True
            , resume=resume
        )

        # train을 지나면 best.pt로 모델을 변경한다
        best_weights = os.path.join(self.train_dir, "weights", "best.pt")
        if os.path.exists(best_weights):
            self.model_path = best_weights
            self.model = YOLO(best_weights)

        return results

    def validate(self, data_yaml: str, split: str = "val", device: str | int = 0) -> dict:
        print(f"\n--- [Val] 모델 성능 검증 시작 (출력 경로: {self.val_dir}) ---")
        
        # os.makedirs(): 디렉토리 생성 (exist_ok=True: 이미 폴더가 존재해도 예외 없이 무시)
        os.makedirs(self.val_dir, exist_ok=True)
        
        metrics = self.model.val(
            data=data_yaml, split=split, project=os.path.abspath(self.exp_dir),
            name="val", exist_ok=True, device=device
        )

        performance_summary = {
            "box_map50_95": float(metrics.box.map),
            "box_map50": float(metrics.box.map50),
            "seg_map50_95": float(metrics.seg.map),
            "seg_map50": float(metrics.seg.map50),
            "precision": float(metrics.box.p.mean()) if hasattr(metrics.box.p, 'mean') else 0.0,
            "recall": float(metrics.box.r.mean()) if hasattr(metrics.box.r, 'mean') else 0.0
        }

        output_json = os.path.join(self.val_dir, "val_metrics.json")
        with open(output_json, "w", encoding="utf-8") as f:
            # json.dump(): 파이썬 딕셔너리 데이터를 JSON 파일로 직렬화 (indent=4: 들여쓰기 4칸 적용)
            json.dump(performance_summary, f, ensure_ascii=False, indent=4)

        return performance_summary

    def predict(self, source: str | list, conf: float = 0.5, imgsz: int = 640, device: str | int = 0) -> list:
        print(f"\n--- [Predict] 추론 및 메타데이터 추출 시작 (출력 경로: {self.predict_dir}) ---")
        
        # os.makedirs(): 결과 JSON 저장용 예측 디렉토리 생성
        os.makedirs(self.predict_dir, exist_ok=True)

        results = self.model.predict(
            source=source, conf=conf, imgsz=imgsz, project=os.path.abspath(self.exp_dir),
            name="predict", exist_ok=True, save=False, save_txt=False, device=device
        )

        all_images_data = []

        for r_idx, result in enumerate(results):
            boxes = result.boxes
            masks = result.masks
            image_path = result.path

            annotated_image = result.plot(
                line_width=1,
                font_size=10,
                labels=True,
                boxes=True,
                masks=True,
            )
            output_image_name = f"{os.path.splitext(os.path.basename(image_path))[0]}_pred.jpg"
            cv2.imwrite(os.path.join(self.predict_dir, output_image_name), annotated_image)

            img_result = {
                "image_index": r_idx,
                "image_path": image_path,
                "detected_objects": []
            }

            if boxes is not None and len(boxes) > 0:
                for i in range(len(boxes)):
                    class_id = int(boxes.cls[i].item())
                    class_name = self.model.names[class_id]
                    confidence = float(boxes.conf[i].item())
                    xyxy = boxes.xyxy[i].tolist()

                    polygon = None
                    if masks is not None and len(masks.xy) > i:
                        polygon = masks.xy[i].tolist()

                    obj_info = {
                        "class_id": class_id,
                        "class_name": class_name,
                        "confidence": round(confidence, 4),
                        "bounding_box": [round(c, 2) for c in xyxy],
                        "polygon": polygon
                    }
                    img_result["detected_objects"].append(obj_info)

            all_images_data.append(img_result)

        output_json = os.path.join(self.predict_dir, "inference_results.json")
        with open(output_json, "w", encoding="utf-8") as f:
            json.dump(all_images_data, f, ensure_ascii=False, indent=4)

        return all_images_data


# ==========================================
# 스크립트 직접 실행부 (Main)   /    train,val,test를 동시에 진행한다면 best.pt가 만들어 지고 그것으로 모델을 변경한 뒤에 val가 test를 진행함
# ==========================================
if __name__ == "__main__":
    # 데이터셋 및 추론 대상 경로 설정
    model_path = input("모델 파일 경로를 입력하세요: ").strip()
    yaml_path = os.path.join(DATASET_DIR, "data.yaml")
    test_source = input("추론할 이미지 또는 폴더 경로를 입력하세요: ").strip()


    # [파이프라인 인스턴스 초기화]
    pipeline = YOLOSegmentPipeline(
        model_path=model_path,               # 베이스 모델 또는 학습된 가중치(.pt) 경로
        base_dir=RUNS_DIR,
        exp_name="yolo11_climbing_seg_vr"       # 실험 프로젝트 명칭
    )

    # ------------------------------------------
    # 1. 모델 학습 (Train)
    # ------------------------------------------
    pipeline.train(
        data_yaml=yaml_path,  # dataset 설정 파일 경로
        epochs=100,           # 총 학습 에폭 수
        imgsz=640,            # 입력 이미지 리사이즈 크기
        batch=16,             # 배치 사이즈
        device=0,             # GPU 장치 번호 (또는 'cpu')
        patience=20,          # 조기 종료(Early Stopping) 대기 에폭
        resume=False          # last.pt부터 이어서 학습할지 여부
    )

    # ------------------------------------------
    # 2. 성능 검증 (Val)
    # ------------------------------------------
    pipeline.validate(
        data_yaml=yaml_path,    # dataset 설정 파일 경로
        split="val",            # 검증 데이터셋 분할 영역 ('val' 또는 'test')
        device=0                # GPU 장치 번호 (또는 'cpu')
    )

    # ------------------------------------------
    # 3. 추론 및 인스턴스 크롭 (Predict & Crop)
    # ------------------------------------------
    pipeline.predict(
        source=test_source,     # 추론 대상 (단일 이미지 경로, 이미지 리스트, 폴더 경로)
        conf=0.5,               # 탐지 신뢰도 임계값 (Confidence Threshold)
        imgsz=640,              # 추론시 입력 이미지 리사이즈 크기
        device="0"                # GPU 장치 번호 (또는 'cpu')
    )