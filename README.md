# Smart Health Wearable AI Pipeline (Smart Park)

An intelligent, distributed digital ecosystem designed to integrate IoT technologies into natural environments. The **Smart Park** project focuses on the **Sila Grande** case study, providing hikers, athletes, and tourists with real-time health monitoring, activity recognition, and safety alerts while transforming the park into a connected, intelligent ecosystem.

---

## 🏗️ System Architecture & Distributed Ecosystem
<img width="1024" height="559" alt="Architettura" src="https://github.com/user-attachments/assets/40edcfdf-4619-4f58-9637-4d439b8a1537" />

The system follows a **Cloud-Edge** paradigm where devices and software modules collaborate to provide seamless services even in remote areas where network connectivity is intermittent or absent.

*   **Edge Processing (Smartphone):** Acts as the central coordinator, running Machine Learning models locally for immediate feedback, reducing network traffic by processing raw data on-device, and providing offline persistence via local storage.
*   **Sensor Suite:**
    *   **Shimmer Sensor:** Produces a continuous accelerometric stream at 50 Hz for activity recognition.
    *   **Smart Ring:** Performs asynchronous measurement of physiological parameters, including Heart Rate, $\text{SpO}_2$, and Blood Pressure.
*   **Cloud Infrastructure (AWS):** A serverless backend using **AWS Lambda** for data processing and **Amazon DynamoDB** for persistent, scalable storage, protected by HTTPS/TLS and API keys.
*   **Prototype Interface:** An **ESP32-based Web Server** provides remote access to historical data for logged users, ensuring multi-workstation accessibility.

---

## 🧠 Core Functional Capabilities

### 1. Advanced Activity Detection
*   **ML Pipeline:** Uses a CNN model running on **TensorFlow Lite**, where data is segmented into 100-sample windows (2 seconds) with a 50% overlap for high-accuracy inference.
*   **Classification:** Distinguishes between Moving and Not-moving, with specialized recognition for Walking, Sitting, Standing, and Jogging.

### 2. Alerting & Emergency Module
*   **Multi-Parametric Validation:** Correlates physiological data (e.g., heart rate) with activity context (e.g., running vs. resting) to minimize false positives (e.g., detecting tachycardia at rest).
*   **Fail-Safe Mechanism:** Features an "Emergency Overlay" that wakes the display and provides a visual countdown window, allowing users to manually dismiss false alarms before emergency synchronization is triggered.

### 3. Data Optimization & Batching
*   **Shimmer Optimization:** Raw signals are cached in volatile RAM and summarized into X, Y, Z means. An atomic flush every 5 minutes sends JSON batches to AWS, significantly reducing network traffic, energy consumption, and cloud costs.

---

## 🛠️ Technical Specifications

| Component | Technology |
| :--- | :--- |
| **Mobile OS** | Android 8.0 (API 26+) |
| **Edge Intelligence** | TensorFlow Lite (CNN-LSTM architecture) |
| **Communication** | Bluetooth Low Energy (BLE) |
| **Local Storage** | SQLite (isolated per UID) |
| **Cloud Backend** | AWS Lambda, DynamoDB, API Gateway |
| **IoT Web Interface** | ESP32 (WiFi, ArduinoJson, WebServer) |

---

## 🛡️ Security, Privacy & Constraints
*   **Multi-User Isolation:** Firebase Authentication generates a unique UID for each user, creating an isolated local SQLite sandbox.
*   **Privacy Adherence:** Personal data remains on the device; cloud transmissions are anonymized and associated only with a UID.
*   **Resource-Constrained Optimization:** Designed to minimize CPU and battery usage through batching, careful thread management, and the avoidance of continuous raw data streaming.

---

## 🔮 Future Enhancements
*   **Expanded ML:** Integration of activity-specific models (climbing, cycling, steep slope descent).
*   **Adaptive Profiling:** User-configurable "Energy-vs-Latency" profiles to balance battery life during extended excursions.
*   **iOS Porting:** Expanding ecosystem accessibility to Apple devices.
*   **Advanced Anomaly Detection:** Specialized edge models for cardiac arrhythmia and fall detection.

---

*Developed by: Desando Francesco, Marzano Mario, Masdea Miriam, Turano Edoardo.*
