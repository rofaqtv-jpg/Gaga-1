#include <jni.h>
#include <android/log.h>
#include <btBulletDynamicsCommon.h>
#include <cmath>
#include <algorithm>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "TURRINI", __VA_ARGS__)

struct WheelState {
    float steer=0, spin=0, compression=0;
};

class RealVehicle {
public:
    btDefaultCollisionConfiguration* cfg=nullptr;
    btCollisionDispatcher* dispatcher=nullptr;
    btDbvtBroadphase* broadphase=nullptr;
    btSequentialImpulseConstraintSolver* solver=nullptr;
    btDiscreteDynamicsWorld* world=nullptr;
    btRigidBody* chassis=nullptr;
    btRaycastVehicle* vehicle=nullptr;
    btCollisionShape* groundShape=nullptr;
    btCollisionShape* chassisShape=nullptr;
    btDefaultVehicleRaycaster* raycaster=nullptr;
    btRaycastVehicle::btVehicleTuning tuning;
    WheelState wheels[4];

    float x=0,y=1,z=0,yaw=0,pitch=0,roll=0,speed=0;
    bool ready=false;

    void init() {
        if (ready) return;

        cfg = new btDefaultCollisionConfiguration();
        dispatcher = new btCollisionDispatcher(cfg);
        broadphase = new btDbvtBroadphase();
        solver = new btSequentialImpulseConstraintSolver();
        world = new btDiscreteDynamicsWorld(dispatcher,broadphase,solver,cfg);
        world->setGravity(btVector3(0,-9.81f,0));

        // Large static ground; visual terrain is generated independently.
        groundShape = new btBoxShape(btVector3(500,1,500));
        btTransform gt; gt.setIdentity(); gt.setOrigin(btVector3(0,-1,0));
        auto* gm = new btDefaultMotionState(gt);
        btRigidBody::btRigidBodyConstructionInfo gi(0,gm,groundShape,btVector3(0,0,0));
        world->addRigidBody(new btRigidBody(gi));

        // Vehicle chassis
        chassisShape = new btCompoundShape();
        auto* body = new btBoxShape(btVector3(0.95f,0.38f,1.75f));
        btTransform bt; bt.setIdentity(); bt.setOrigin(btVector3(0,0,0));
        chassisShape->addChildShape(bt,body);

        btVector3 inertia(0,0,0);
        chassisShape->calculateLocalInertia(1050,inertia);
        btTransform ct; ct.setIdentity(); ct.setOrigin(btVector3(0,1.6f,0));
        auto* cm = new btDefaultMotionState(ct);
        btRigidBody::btRigidBodyConstructionInfo ci(1050,cm,chassisShape,inertia);
        chassis = new btRigidBody(ci);
        chassis->setActivationState(DISABLE_DEACTIVATION);
        chassis->setDamping(0.08f,0.18f);
        world->addRigidBody(chassis);

        raycaster = new btDefaultVehicleRaycaster(world);
        vehicle = new btRaycastVehicle(tuning,chassis,raycaster);
        world->addVehicle(vehicle);
        vehicle->setCoordinateSystem(0,1,2);

        // Front/rear wheel positions.
        const float sx=0.86f, sz=1.22f, rest=0.58f, radius=0.34f;
        for(int i=0;i<4;i++){
            float xx = (i%2==0 ? sx : -sx);
            float zz = (i<2 ? sz : -sz);
            btVector3 conn(xx,-0.34f,zz);
            btWheelInfo& w = vehicle->addWheel(
                conn, btVector3(0,-1,0), btVector3(-1,0,0),
                rest, radius, tuning, i<2
            );
            w.m_suspensionStiffness=38.0f;
            w.m_wheelsDampingRelaxation=2.8f;
            w.m_wheelsDampingCompression=5.0f;
            w.m_frictionSlip=2.0f;
            w.m_rollInfluence=0.08f;
            w.m_maxSuspensionTravelCm=32.0f;
            w.m_maxSuspensionForce=8000.0f;
        }

        ready=true;
        LOGI("Realistic vehicle engine initialized");
    }

    void update(float dt, bool gas, bool brake, bool left, bool right) {
        if(!ready) init();
        dt=std::clamp(dt,0.001f,0.05f);

        const float steerTarget = (left?-0.52f:0.0f) + (right?0.52f:0.0f);
        float steer = vehicle->getSteeringValue(0);
        steer += (steerTarget-steer)*std::min(1.0f,dt*8.0f);
        vehicle->setSteeringValue(steer,0);
        vehicle->setSteeringValue(steer,1);

        // Rear-wheel drive with progressive torque.
        float engine = gas ? 3600.0f : 0.0f;
        float brakeForce = brake ? 180.0f : 0.0f;
        vehicle->applyEngineForce(engine,2);
        vehicle->applyEngineForce(engine,3);
        for(int i=0;i<4;i++) vehicle->setBrake(brakeForce,i);

        // Mild stability aid for a controllable mobile driving experience.
        btVector3 av=chassis->getAngularVelocity();
        chassis->setAngularVelocity(btVector3(av.x()*0.97f,av.y(),av.z()*0.97f));

        world->stepSimulation(dt,4,1.0f/60.0f);

        btTransform t=chassis->getWorldTransform();
        btVector3 p=t.getOrigin();
        x=p.x(); y=p.y(); z=p.z();

        btQuaternion q=t.getRotation();
        float siny_cosp=2*(q.w()*q.y()+q.x()*q.z());
        float cosy_cosp=1-2*(q.y()*q.y()+q.z()*q.z());
        yaw=std::atan2(siny_cosp,cosy_cosp);
        float sinp=2*(q.w()*q.x()-q.z()*q.y());
        pitch=std::asin(std::clamp(sinp,-1.0f,1.0f));
        float sinr=2*(q.w()*q.z()+q.x()*q.y());
        float cosr=1-2*(q.x()*q.x()+q.z()*q.z());
        roll=std::atan2(sinr,cosr);

        speed=chassis->getLinearVelocity().length()*3.6f;

        for(int i=0;i<4;i++){
            auto& w=vehicle->getWheelInfo(i);
            wheels[i].spin += speed*dt*2.2f;
            wheels[i].compression=w.m_raycastInfo.suspensionLength;
            wheels[i].steer=(i<2)?steer:0;
        }
    }
};

static RealVehicle g;

extern "C" JNIEXPORT void JNICALL
Java_com_turrinistudio_gtav_MainActivity_nativeInit(JNIEnv*,jobject){g.init();}

extern "C" JNIEXPORT void JNICALL
Java_com_turrinistudio_gtav_MainActivity_nativeUpdate(JNIEnv*,jobject,jfloat dt,
 jboolean gas,jboolean brake,jboolean left,jboolean right){
    g.update(dt,gas,brake,left,right);
}

extern "C" JNIEXPORT jfloat JNICALL Java_com_turrinistudio_gtav_MainActivity_nativeX(JNIEnv*,jobject){return g.x;}
extern "C" JNIEXPORT jfloat JNICALL Java_com_turrinistudio_gtav_MainActivity_nativeY(JNIEnv*,jobject){return g.y;}
extern "C" JNIEXPORT jfloat JNICALL Java_com_turrinistudio_gtav_MainActivity_nativeZ(JNIEnv*,jobject){return g.z;}
extern "C" JNIEXPORT jfloat JNICALL Java_com_turrinistudio_gtav_MainActivity_nativeYaw(JNIEnv*,jobject){return g.yaw;}
extern "C" JNIEXPORT jfloat JNICALL Java_com_turrinistudio_gtav_MainActivity_nativePitch(JNIEnv*,jobject){return g.pitch;}
extern "C" JNIEXPORT jfloat JNICALL Java_com_turrinistudio_gtav_MainActivity_nativeRoll(JNIEnv*,jobject){return g.roll;}
extern "C" JNIEXPORT jfloat JNICALL Java_com_turrinistudio_gtav.MainActivity_nativeSpeed(JNIEnv*,jobject){return g.speed;}
