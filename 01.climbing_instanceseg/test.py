import os

import cv2
from ultralytics import YOLO


PROJECT_DIR = os.path.dirname(os.path.abspath(__file__))


def resolve_path(path: str) -> str:
	if os.path.isabs(path):
		return path
	return os.path.join(PROJECT_DIR, path)


def main() -> None:
	model_path = r"C:\Users\zion0\Desktop\my\AutoHold-Route_holdcraft\01.climbing_instanceseg\runs\yolo11_climbing_seg_vr1.1\train\weights\best.pt"
	source_path =r"C:\Users\zion0\Desktop\my\AutoHold-Route_holdcraft\01.climbing_instanceseg\test\KakaoTalk_20260821_135216840.jpg"

	if not os.path.isfile(model_path):
		raise FileNotFoundError(f"모델 파일을 찾을 수 없습니다: {model_path}")
	if not os.path.exists(source_path):
		raise FileNotFoundError(f"추론 대상 경로를 찾을 수 없습니다: {source_path}")

	if os.path.isdir(source_path):
		source = [
			os.path.join(source_path, file_name)
			for file_name in os.listdir(source_path)
			if file_name.lower().endswith((".jpg", ".jpeg", ".png"))
			and not file_name.lower().endswith("_pred.jpg")
		]
		output_dir = source_path
	else:
		source = [source_path]
		output_dir = os.path.dirname(source_path)

	if not source:
		raise FileNotFoundError(f"추론할 이미지가 없습니다: {source_path}")

	model = YOLO(model_path)
	results =model.predict(
		source=source,
		conf=0.5,
		imgsz=640,
		device="cpu",
		save=False,
	)

	for result in results:
		image_name = os.path.splitext(os.path.basename(result.path))[0]
		output_path = os.path.join(output_dir, f"{image_name}_pred.jpg")
		annotated_image = result.plot(line_width=1, font_size=10)
		cv2.imwrite(output_path, annotated_image)

	print(f"Predicted images: {len(results)}")
	print(f"Results saved to: {output_dir}")


if __name__ == "__main__":
	main()
