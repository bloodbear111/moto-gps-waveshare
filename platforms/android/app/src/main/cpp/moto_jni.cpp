// Android adaptation layer over the upstream `shared/` C++ core.
//
// This file is glue only: it converts JVM values to/from the shared C++
// types and never re-implements wire layouts, route matching or coordinate
// transforms. The iOS Objective-C++ bridges solve the same problem with UIKit
// types; none of that code is portable to Android, so the projection is new.

#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "moto/ble_protocol/ble_protocol.hpp"
#include "moto_coordinates.hpp"
#include "moto_golden_selftest.h"
#include "nav_app/nav_app.hpp"

namespace {

using moto::ble::Bytes;
using moto::ble::ByteView;

constexpr const char* kSnapshotInputClass =
    "io/github/bloodbear111/motogps/protocol/MotoSnapshotInput";
constexpr const char* kInboundBufferClass =
    "io/github/bloodbear111/motogps/protocol/MotoInboundBuffer";
constexpr const char* kMapSceneInputClass =
    "io/github/bloodbear111/motogps/protocol/MotoMapSceneInput";
constexpr const char* kMapRoadInputClass =
    "io/github/bloodbear111/motogps/protocol/MotoMapRoadInput";
constexpr const char* kMapBuildingInputClass =
    "io/github/bloodbear111/motogps/protocol/MotoMapBuildingInput";
constexpr const char* kMapPointInputClass =
    "io/github/bloodbear111/motogps/protocol/MotoMapPointInput";
constexpr const char* kNavSnapshotBufferClass =
    "io/github/bloodbear111/motogps/navigation/MotoNavSnapshotBuffer";
constexpr const char* kNavCommandClass =
    "io/github/bloodbear111/motogps/navigation/MotoNavCommand";
constexpr const char* kProtocolExceptionClass =
    "io/github/bloodbear111/motogps/protocol/MotoProtocolException";

// ---------------------------------------------------------------------------
// Small JNI helpers
// ---------------------------------------------------------------------------

struct ClassFieldIds {
    jclass cls = nullptr;
    bool ok = false;
};

void ThrowProtocolError(JNIEnv* env, moto::ble::Error error,
                        std::size_t offset) {
    jclass exception_class = env->FindClass(kProtocolExceptionClass);
    if (exception_class == nullptr) {
        env->ExceptionClear();
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      moto::ble::to_string(error));
        return;
    }
    jmethodID ctor = env->GetMethodID(exception_class, "<init>",
                                      "(Ljava/lang/String;II)V");
    if (ctor == nullptr) {
        env->ExceptionClear();
        env->ThrowNew(exception_class, moto::ble::to_string(error));
        return;
    }
    const std::string message =
        std::string("BLE v1: ") + moto::ble::to_string(error) + " (offset " +
        std::to_string(offset) + ")";
    jstring jmessage = env->NewStringUTF(message.c_str());
    jobject exception = env->NewObject(
        exception_class, ctor, jmessage, static_cast<jint>(error),
        static_cast<jint>(offset));
    if (exception != nullptr) env->Throw(static_cast<jthrowable>(exception));
}

void ThrowRuntime(JNIEnv* env, const char* message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message);
}

std::string BytesToString(JNIEnv* env, jbyteArray array) {
    if (array == nullptr) return {};
    const jsize length = env->GetArrayLength(array);
    if (length <= 0) return {};
    std::string out(static_cast<std::size_t>(length), '\0');
    env->GetByteArrayRegion(array, 0, length,
                            reinterpret_cast<jbyte*>(&out[0]));
    return out;
}

jbyteArray BytesToArray(JNIEnv* env, ByteView bytes) {
    if (bytes.size > static_cast<std::size_t>(INT32_MAX)) return nullptr;
    jbyteArray array =
        env->NewByteArray(static_cast<jsize>(bytes.size));
    if (array == nullptr || bytes.size == 0) return array;
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(bytes.size),
                            reinterpret_cast<const jbyte*>(bytes.data));
    return array;
}

jobjectArray FramesToArray(JNIEnv* env,
                           const std::vector<Bytes>& frames) {
    jclass byte_array_class = env->FindClass("[B");
    if (byte_array_class == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(frames.size()), byte_array_class, nullptr);
    if (result == nullptr) return nullptr;
    for (std::size_t i = 0; i < frames.size(); ++i) {
        jbyteArray element = BytesToArray(env, ByteView(frames[i]));
        if (element == nullptr) return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element);
        env->DeleteLocalRef(element);
    }
    return result;
}

jobjectArray StringsToArray(JNIEnv* env,
                            const std::vector<std::string>& values) {
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(values.size()), string_class, nullptr);
    if (result == nullptr) return nullptr;
    for (std::size_t i = 0; i < values.size(); ++i) {
        jstring element = env->NewStringUTF(values[i].c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element);
        env->DeleteLocalRef(element);
    }
    return result;
}

std::vector<double> ReadDoubleArray(JNIEnv* env, jdoubleArray array) {
    std::vector<double> out;
    if (array == nullptr) return out;
    const jsize length = env->GetArrayLength(array);
    if (length <= 0) return out;
    out.resize(static_cast<std::size_t>(length));
    env->GetDoubleArrayRegion(array, 0, length, out.data());
    return out;
}

std::vector<std::string> ReadStringArray(JNIEnv* env,
                                         jobjectArray array) {
    std::vector<std::string> out;
    if (array == nullptr) return out;
    const jsize length = env->GetArrayLength(array);
    out.reserve(static_cast<std::size_t>(length));
    for (jsize i = 0; i < length; ++i) {
        auto* element = static_cast<jbyteArray>(
            env->GetObjectArrayElement(array, i));
        out.push_back(BytesToString(env, element));
        if (element != nullptr) env->DeleteLocalRef(element);
    }
    return out;
}

// Generic struct-field getter used by the input/output mirror classes.
struct Field {
    jfieldID id = nullptr;
    bool valid() const { return id != nullptr; }
};

struct FieldSet {
    jclass cls = nullptr;
    std::vector<std::pair<std::string, jfieldID>> cache;

    explicit FieldSet(JNIEnv* env, const char* name) {
        cls = env->FindClass(name);
    }

    Field get(JNIEnv* env, const char* name, const char* signature) {
        if (cls == nullptr) return {};
        for (const auto& entry : cache) {
            if (entry.first == name) return {entry.second};
        }
        jfieldID id = env->GetFieldID(cls, name, signature);
        if (id == nullptr) return {};
        cache.emplace_back(name, id);
        return {id};
    }
};

std::int32_t ReadInt(JNIEnv* env, jobject object, FieldSet& fields,
                     const char* name) {
    const Field field = fields.get(env, name, "I");
    return field.valid() ? env->GetIntField(object, field.id) : 0;
}

bool ReadBool(JNIEnv* env, jobject object, FieldSet& fields,
              const char* name) {
    const Field field = fields.get(env, name, "Z");
    return field.valid() && env->GetBooleanField(object, field.id) == JNI_TRUE;
}

std::string ReadBytesField(JNIEnv* env, jobject object, FieldSet& fields,
                           const char* name) {
    const Field field = fields.get(env, name, "[B");
    if (!field.valid()) return {};
    auto* array =
        static_cast<jbyteArray>(env->GetObjectField(object, field.id));
    const std::string out = BytesToString(env, array);
    if (array != nullptr) env->DeleteLocalRef(array);
    return out;
}

void WriteInt(JNIEnv* env, jobject object, FieldSet& fields,
              const char* name, jint value) {
    const Field field = fields.get(env, name, "I");
    if (field.valid()) env->SetIntField(object, field.id, value);
}

void WriteLong(JNIEnv* env, jobject object, FieldSet& fields,
               const char* name, jlong value) {
    const Field field = fields.get(env, name, "J");
    if (field.valid()) env->SetLongField(object, field.id, value);
}

void WriteDouble(JNIEnv* env, jobject object, FieldSet& fields,
                 const char* name, jdouble value) {
    const Field field = fields.get(env, name, "D");
    if (field.valid()) env->SetDoubleField(object, field.id, value);
}

void WriteBool(JNIEnv* env, jobject object, FieldSet& fields,
               const char* name, bool value) {
    const Field field = fields.get(env, name, "Z");
    if (field.valid()) {
        env->SetBooleanField(object, field.id, value ? JNI_TRUE : JNI_FALSE);
    }
}

void WriteString(JNIEnv* env, jobject object, FieldSet& fields,
                 const char* name, const std::string& value) {
    const Field field = fields.get(env, name, "Ljava/lang/String;");
    if (!field.valid()) return;
    jstring converted = env->NewStringUTF(value.c_str());
    env->SetObjectField(object, field.id, converted);
    if (converted != nullptr) env->DeleteLocalRef(converted);
}

void WriteDoubleArrayField(JNIEnv* env, jobject object, FieldSet& fields,
                           const char* name,
                           const std::vector<double>& values) {
    const Field field = fields.get(env, name, "[D");
    if (!field.valid()) return;
    auto* array = static_cast<jdoubleArray>(
        env->GetObjectField(object, field.id));
    if (array == nullptr) return;
    const jsize length = env->GetArrayLength(array);
    const jsize count = std::min<jsize>(
        length, static_cast<jsize>(values.size()));
    if (count > 0) {
        env->SetDoubleArrayRegion(array, 0, count, values.data());
    }
    env->DeleteLocalRef(array);
}

// ---------------------------------------------------------------------------
// Codec
// ---------------------------------------------------------------------------

struct CodecHandle {
    explicit CodecHandle(std::size_t frame_size)
        : maximum_frame_size(std::clamp<std::size_t>(
              frame_size, moto::ble::kFrameOverhead + 1,
              moto::ble::kMaxBleAttributeValueSize)) {}

    std::size_t maximum_frame_size;
    moto::ble::SequenceGenerator sequence;
    std::uint16_t last_encoded_sequence = 0;
    moto::ble::Reassembler reassembler;
};

CodecHandle* AsCodec(jlong handle) {
    return reinterpret_cast<CodecHandle*>(static_cast<intptr_t>(handle));
}

jobjectArray EncodeMessage(JNIEnv* env, CodecHandle* codec,
                           const moto::ble::Message& message,
                           std::uint8_t flags) {
    const auto payload = moto::ble::encode_message(message);
    if (!payload.ok()) {
        ThrowProtocolError(env, payload.error, payload.offset);
        return nullptr;
    }
    const std::uint16_t sequence = codec->sequence.next();
    const auto frames = moto::ble::fragment_message(
        moto::ble::message_type(message), sequence, ByteView(payload.value),
        codec->maximum_frame_size, flags);
    if (!frames.ok()) {
        ThrowProtocolError(env, frames.error, frames.offset);
        return nullptr;
    }
    codec->last_encoded_sequence = sequence;
    return FramesToArray(env, frames.value);
}

moto::ble::ConnectionStatus PhoneStatus(CodecHandle* codec,
                                        moto::ble::ConnectionState state,
                                        std::uint32_t session_id) {
    moto::ble::ConnectionStatus status;
    status.role = moto::ble::EndpointRole::Phone;
    status.state = state;
    status.capabilities = moto::ble::CapabilityNavigation |
                          moto::ble::CapabilityRouteGeometry |
                          moto::ble::CapabilityTraffic |
                          moto::ble::CapabilityMediaState |
                          moto::ble::CapabilityTouchCommands |
                          moto::ble::CapabilityMusicCommands |
                          moto::ble::CapabilityCommandAck |
                          moto::ble::CapabilityMapScene;
    status.session_id = session_id;
    status.max_frame_size =
        static_cast<std::uint16_t>(codec->maximum_frame_size);
    status.heartbeat_interval_ms = 1'000;
    return status;
}

bool ReadSnapshotInput(JNIEnv* env, jobject input,
                       moto::ble::NavigationSnapshot* out) {
    FieldSet fields(env, kSnapshotInputClass);
    if (fields.cls == nullptr) return false;

    out->state = static_cast<moto::ble::NavigationState>(
        ReadInt(env, input, fields, "state"));
    out->network = static_cast<moto::ble::NetworkState>(
        ReadInt(env, input, fields, "network"));
    out->display_page = static_cast<moto::ble::DisplayPage>(
        ReadInt(env, input, fields, "displayPage"));
    out->maneuver =
        static_cast<moto::ble::Maneuver>(ReadInt(env, input, fields, "maneuver"));
    out->traffic = static_cast<moto::ble::TrafficLevel>(
        ReadInt(env, input, fields, "traffic"));

    std::uint16_t flags = 0;
    if (ReadBool(env, input, fields, "hasDestination")) {
        flags |= moto::ble::NavigationHasDestination;
    }
    if (ReadBool(env, input, fields, "hasFix")) {
        flags |= moto::ble::NavigationHasFix;
    }
    if (ReadBool(env, input, fields, "gnssStale")) {
        flags |= moto::ble::NavigationGnssStale;
    }
    if (ReadBool(env, input, fields, "offRoute")) {
        flags |= moto::ble::NavigationOffRoute;
    }
    if (ReadBool(env, input, fields, "hasNextManeuver")) {
        flags |= moto::ble::NavigationHasNextManeuver;
    }
    if (ReadBool(env, input, fields, "routeRequestInFlight")) {
        flags |= moto::ble::NavigationRouteRequestInFlight;
    }
    if (ReadBool(env, input, fields, "trafficRequestInFlight")) {
        flags |= moto::ble::NavigationTrafficRequestInFlight;
    }
    if (ReadBool(env, input, fields, "hasRouteView")) {
        flags |= moto::ble::NavigationHasRouteView;
    }
    out->flags = flags;

    out->route_token = static_cast<std::uint32_t>(
        ReadInt(env, input, fields, "routeToken"));
    out->route_generation = static_cast<std::uint32_t>(
        ReadInt(env, input, fields, "routeGeneration"));
    out->maneuver_id = static_cast<std::uint32_t>(
        ReadInt(env, input, fields, "maneuverId"));
    out->distance_to_maneuver_m = static_cast<std::uint32_t>(
        ReadInt(env, input, fields, "distanceToManeuverM"));
    out->remaining_distance_m = static_cast<std::uint32_t>(
        ReadInt(env, input, fields, "remainingDistanceM"));
    out->remaining_duration_s = static_cast<std::uint32_t>(
        ReadInt(env, input, fields, "remainingDurationS"));
    out->route_progress_m = static_cast<std::uint32_t>(
        ReadInt(env, input, fields, "routeProgressM"));
    out->total_distance_m = static_cast<std::uint32_t>(
        ReadInt(env, input, fields, "totalDistanceM"));
    out->speed_deci_kph = static_cast<std::uint16_t>(
        ReadInt(env, input, fields, "speedDeciKph"));
    out->speed_limit_kph = static_cast<std::uint16_t>(
        ReadInt(env, input, fields, "speedLimitKph"));
    out->heading_cdeg = static_cast<std::uint16_t>(
        ReadInt(env, input, fields, "headingCdeg"));
    out->accuracy_dm = static_cast<std::uint16_t>(
        ReadInt(env, input, fields, "accuracyDm"));
    out->cross_track_dm = static_cast<std::uint16_t>(
        ReadInt(env, input, fields, "crossTrackDm"));
    out->roundabout_exit = static_cast<std::uint8_t>(
        ReadInt(env, input, fields, "roundaboutExit"));
    // UTF-8 byte arrays, not modified UTF-8: the shared encoder measures byte
    // lengths and rejects values that exceed the v1 string budgets.
    out->road_name = ReadBytesField(env, input, fields, "roadNameUtf8");
    out->instruction = ReadBytesField(env, input, fields, "instructionUtf8");
    return true;
}

bool ReadMapScene(JNIEnv* env, jobject input, moto::ble::MapScene* out) {
    FieldSet scene_fields(env, kMapSceneInputClass);
    if (scene_fields.cls == nullptr) return false;

    out->coordinate_system = moto::ble::CoordinateSystem::Gcj02;
    out->scene_revision = static_cast<std::uint32_t>(
        ReadInt(env, input, scene_fields, "sceneRevision"));
    out->view_origin.latitude_e6 = ReadInt(env, input, scene_fields, "originLatitudeE6");
    out->view_origin.longitude_e6 = ReadInt(env, input, scene_fields, "originLongitudeE6");
    out->radius_m = static_cast<std::uint16_t>(
        ReadInt(env, input, scene_fields, "radiusM"));

    FieldSet point_fields(env, kMapPointInputClass);
    FieldSet road_fields(env, kMapRoadInputClass);
    FieldSet building_fields(env, kMapBuildingInputClass);
    if (point_fields.cls == nullptr || road_fields.cls == nullptr ||
        building_fields.cls == nullptr) {
        return false;
    }

    auto read_points = [&](jobjectArray points) {
        std::vector<moto::ble::GeoPointE6> result;
        if (points == nullptr) return result;
        const jsize count = env->GetArrayLength(points);
        result.reserve(static_cast<std::size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            jobject point = env->GetObjectArrayElement(points, i);
            if (point == nullptr) continue;
            moto::ble::GeoPointE6 value;
            value.latitude_e6 = ReadInt(env, point, point_fields, "latitudeE6");
            value.longitude_e6 = ReadInt(env, point, point_fields, "longitudeE6");
            result.push_back(value);
            env->DeleteLocalRef(point);
        }
        return result;
    };

    auto read_collection = [&](const char* name, const char* element_class,
                               auto&& consume) {
        const std::string signature = "[L" + std::string(element_class) + ";";
        const Field field = scene_fields.get(env, name, signature.c_str());
        if (!field.valid()) return;
        auto* array = static_cast<jobjectArray>(
            env->GetObjectField(input, field.id));
        if (array == nullptr) return;
        const jsize count = env->GetArrayLength(array);
        for (jsize i = 0; i < count; ++i) {
            jobject element = env->GetObjectArrayElement(array, i);
            if (element != nullptr) {
                consume(element);
                env->DeleteLocalRef(element);
            }
        }
        env->DeleteLocalRef(array);
    };

    read_collection("roads", kMapRoadInputClass, [&](jobject road) {
        moto::ble::MapRoadPolyline polyline;
        polyline.road_class = static_cast<moto::ble::MapRoadClass>(
            ReadInt(env, road, road_fields, "roadClass"));
        const Field points =
            road_fields.get(env, "points",
                            ("[L" + std::string(kMapPointInputClass) + ";").c_str());
        if (points.valid()) {
            auto* array = static_cast<jobjectArray>(
                env->GetObjectField(road, points.id));
            polyline.points = read_points(array);
            if (array != nullptr) env->DeleteLocalRef(array);
        }
        out->roads.push_back(std::move(polyline));
    });

    read_collection("buildings", kMapBuildingInputClass, [&](jobject building) {
        moto::ble::MapBuildingFootprint footprint;
        footprint.building_class = static_cast<moto::ble::MapBuildingClass>(
            ReadInt(env, building, building_fields, "buildingClass"));
        const Field points = building_fields.get(
            env, "points",
            ("[L" + std::string(kMapPointInputClass) + ";").c_str());
        if (points.valid()) {
            auto* array = static_cast<jobjectArray>(
                env->GetObjectField(building, points.id));
            footprint.points = read_points(array);
            if (array != nullptr) env->DeleteLocalRef(array);
        }
        out->buildings.push_back(std::move(footprint));
    });
    return true;
}

// ---------------------------------------------------------------------------
// Navigation core
// ---------------------------------------------------------------------------

std::unique_ptr<moto::nav::NavApp>& NavHandle(jlong handle) {
    return *reinterpret_cast<std::unique_ptr<moto::nav::NavApp>*>(
        static_cast<intptr_t>(handle));
}

jobjectArray CommandsToArray(JNIEnv* env,
                             const moto::nav::NavCommands& commands) {
    jclass command_class = env->FindClass(kNavCommandClass);
    if (command_class == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(
        command_class, "<init>",
        "(Ljava/lang/String;IDDDDZLjava/lang/String;)V");
    if (ctor == nullptr) return nullptr;

    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(commands.size()), command_class, nullptr);
    if (result == nullptr) return nullptr;

    for (std::size_t i = 0; i < commands.size(); ++i) {
        const moto::nav::NavCommand& command = commands[i];
        const char* type_name =
            command.type == moto::nav::CommandType::RequestRoute
                ? "request_route"
                : "request_traffic";
        jstring jtype = env->NewStringUTF(type_name);
        jstring jroute_id = env->NewStringUTF(command.route_id.c_str());
        jobject element = env->NewObject(
            command_class, ctor, jtype, static_cast<jint>(command.request_id),
            static_cast<jdouble>(command.route.origin.longitude_deg),
            static_cast<jdouble>(command.route.origin.latitude_deg),
            static_cast<jdouble>(command.route.destination.longitude_deg),
            static_cast<jdouble>(command.route.destination.latitude_deg),
            command.route.is_reroute ? JNI_TRUE : JNI_FALSE, jroute_id);
        env->DeleteLocalRef(jtype);
        env->DeleteLocalRef(jroute_id);
        if (element == nullptr) return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(i), element);
        env->DeleteLocalRef(element);
    }
    return result;
}

void WriteNavSnapshot(JNIEnv* env, jobject out,
                      const moto::nav::NavSnapshot& snapshot) {
    FieldSet fields(env, kNavSnapshotBufferClass);
    if (fields.cls == nullptr) return;

    WriteInt(env, out, fields, "state", static_cast<jint>(snapshot.state));
    WriteInt(env, out, fields, "network", static_cast<jint>(snapshot.network));
    WriteInt(env, out, fields, "displayPage",
             static_cast<jint>(snapshot.display_page));
    WriteBool(env, out, fields, "hasDestination", snapshot.has_destination);
    WriteBool(env, out, fields, "hasUsableFix", snapshot.has_usable_fix);
    WriteBool(env, out, fields, "gnssStale", snapshot.gnss_stale);
    WriteBool(env, out, fields, "offRoute", snapshot.off_route);
    WriteBool(env, out, fields, "routeRequestInFlight",
              snapshot.route_request_in_flight);
    WriteBool(env, out, fields, "trafficRequestInFlight",
              snapshot.traffic_request_in_flight);

    WriteDouble(env, out, fields, "positionLongitudeDeg",
                snapshot.position.longitude_deg);
    WriteDouble(env, out, fields, "positionLatitudeDeg",
                snapshot.position.latitude_deg);
    WriteDouble(env, out, fields, "destinationLongitudeDeg",
                snapshot.destination.longitude_deg);
    WriteDouble(env, out, fields, "destinationLatitudeDeg",
                snapshot.destination.latitude_deg);
    WriteDouble(env, out, fields, "speedMps", snapshot.speed_mps);
    WriteDouble(env, out, fields, "headingDeg", snapshot.heading_deg);
    WriteDouble(env, out, fields, "horizontalAccuracyM",
                snapshot.horizontal_accuracy_m);
    WriteDouble(env, out, fields, "crossTrackDistanceM",
                snapshot.cross_track_distance_m);
    WriteInt(env, out, fields, "speedLimitKph", snapshot.speed_limit_kph);

    WriteBool(env, out, fields, "hasRouteView", snapshot.has_route_view);
    WriteDouble(env, out, fields, "routeViewOriginLongitudeDeg",
                snapshot.route_view_origin.longitude_deg);
    WriteDouble(env, out, fields, "routeViewOriginLatitudeDeg",
                snapshot.route_view_origin.latitude_deg);

    std::vector<double> latitudes;
    std::vector<double> longitudes;
    const std::size_t count = std::min<std::size_t>(
        snapshot.route_view_point_count, moto::nav::kRouteViewPointCapacity);
    latitudes.reserve(count);
    longitudes.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
        latitudes.push_back(snapshot.route_view_points[i].latitude_deg);
        longitudes.push_back(snapshot.route_view_points[i].longitude_deg);
    }
    WriteDoubleArrayField(env, out, fields, "routeViewLatitudes", latitudes);
    WriteDoubleArrayField(env, out, fields, "routeViewLongitudes", longitudes);
    WriteInt(env, out, fields, "routeViewPointCount",
             static_cast<jint>(count));

    WriteString(env, out, fields, "routeId", snapshot.route_id);
    WriteDouble(env, out, fields, "routeProgressM", snapshot.route_progress_m);
    WriteDouble(env, out, fields, "totalDistanceM", snapshot.total_distance_m);
    WriteDouble(env, out, fields, "remainingDistanceM",
                snapshot.remaining_distance_m);
    WriteLong(env, out, fields, "remainingDurationS",
              snapshot.remaining_duration_s);

    WriteBool(env, out, fields, "hasNextManeuver", snapshot.has_next_maneuver);
    WriteInt(env, out, fields, "maneuverId",
             static_cast<jint>(snapshot.next_maneuver.id));
    WriteInt(env, out, fields, "maneuverType",
             static_cast<jint>(snapshot.next_maneuver.type));
    WriteDouble(env, out, fields, "distanceToManeuverM",
                snapshot.distance_to_next_maneuver_m);
    WriteString(env, out, fields, "roadName", snapshot.next_maneuver.road_name);
    WriteString(env, out, fields, "instruction",
                snapshot.next_maneuver.instruction);
    WriteInt(env, out, fields, "roundaboutExit",
             snapshot.next_maneuver.roundabout_exit);
    WriteInt(env, out, fields, "trafficAhead",
             static_cast<jint>(snapshot.traffic_ahead));

    WriteLong(env, out, fields, "nowMs",
              static_cast<jlong>(snapshot.now_ms));
    WriteLong(env, out, fields, "lastFixMs",
              static_cast<jlong>(snapshot.last_fix_ms));
    WriteLong(env, out, fields, "lastTrafficUpdateMs",
              static_cast<jlong>(snapshot.last_traffic_update_ms));
    WriteInt(env, out, fields, "routeGeneration",
             static_cast<jint>(snapshot.route_generation));
}

}  // namespace

// ===========================================================================
// MotoProtocolCodec
// ===========================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeCreate(
    JNIEnv*, jclass, jint maximum_frame_size) {
    auto* codec = new CodecHandle(
        static_cast<std::size_t>(std::max<jint>(0, maximum_frame_size)));
    return static_cast<jlong>(reinterpret_cast<intptr_t>(codec));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete AsCodec(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeSetMaximumFrameSize(
    JNIEnv*, jclass, jlong handle, jint maximum_frame_size) {
    CodecHandle* codec = AsCodec(handle);
    if (codec == nullptr) return;
    codec->maximum_frame_size = std::clamp<std::size_t>(
        static_cast<std::size_t>(std::max<jint>(0, maximum_frame_size)),
        moto::ble::kFrameOverhead + 1, moto::ble::kMaxBleAttributeValueSize);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeResetInbound(
    JNIEnv*, jclass, jlong handle) {
    CodecHandle* codec = AsCodec(handle);
    if (codec != nullptr) codec->reassembler.reset();
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeMaximumFrameSize(
    JNIEnv*, jclass, jlong handle) {
    CodecHandle* codec = AsCodec(handle);
    return codec == nullptr ? 0
                            : static_cast<jint>(codec->maximum_frame_size);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeLastEncodedSequence(
    JNIEnv*, jclass, jlong handle) {
    CodecHandle* codec = AsCodec(handle);
    return codec == nullptr ? 0 : static_cast<jint>(codec->last_encoded_sequence);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeEncodeConnection(
    JNIEnv* env, jclass, jlong handle, jint state, jint session_id) {
    CodecHandle* codec = AsCodec(handle);
    if (codec == nullptr) {
        ThrowRuntime(env, "codec handle is closed");
        return nullptr;
    }
    const auto status = PhoneStatus(
        codec, static_cast<moto::ble::ConnectionState>(state),
        static_cast<std::uint32_t>(session_id));
    return EncodeMessage(env, codec, moto::ble::Message{status}, 0);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeEncodeHeartbeat(
    JNIEnv* env, jclass, jlong handle, jint session_id, jint monotonic_ms) {
    CodecHandle* codec = AsCodec(handle);
    if (codec == nullptr) {
        ThrowRuntime(env, "codec handle is closed");
        return nullptr;
    }
    moto::ble::Heartbeat heartbeat;
    heartbeat.session_id = static_cast<std::uint32_t>(session_id);
    heartbeat.monotonic_ms = static_cast<std::uint32_t>(monotonic_ms);
    heartbeat.status_flags = 0;
    return EncodeMessage(env, codec, moto::ble::Message{heartbeat}, 0);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeEncodeAck(
    JNIEnv* env, jclass, jlong handle, jint sequence, jint status,
    jint command_id) {
    CodecHandle* codec = AsCodec(handle);
    if (codec == nullptr) {
        ThrowRuntime(env, "codec handle is closed");
        return nullptr;
    }
    moto::ble::Ack ack;
    ack.acknowledged_sequence = static_cast<std::uint16_t>(sequence);
    ack.status = static_cast<moto::ble::AckStatus>(status);
    ack.command_id = static_cast<std::uint16_t>(command_id);
    return EncodeMessage(env, codec, moto::ble::Message{ack}, 0);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeEncodeNavigationSnapshot(
    JNIEnv* env, jclass, jlong handle, jobject input) {
    CodecHandle* codec = AsCodec(handle);
    if (codec == nullptr || input == nullptr) {
        ThrowRuntime(env, "codec handle is closed or input is null");
        return nullptr;
    }
    moto::ble::NavigationSnapshot snapshot;
    if (!ReadSnapshotInput(env, input, &snapshot)) {
        if (env->ExceptionCheck()) return nullptr;
        ThrowRuntime(env, "MotoSnapshotInput fields are missing");
        return nullptr;
    }
    return EncodeMessage(env, codec, moto::ble::Message{snapshot}, 0);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeEncodeRouteGeometry(
    JNIEnv* env, jclass, jlong handle, jbyteArray route_id, jint generation,
    jint origin_latitude_e6, jint origin_longitude_e6, jintArray latitudes_e6,
    jintArray longitudes_e6, jint point_count) {
    CodecHandle* codec = AsCodec(handle);
    if (codec == nullptr) {
        ThrowRuntime(env, "codec handle is closed");
        return nullptr;
    }
    const std::string route_id_bytes = BytesToString(env, route_id);
    if (route_id_bytes.empty() || point_count <= 0 || latitudes_e6 == nullptr ||
        longitudes_e6 == nullptr) {
        // Matches the iOS bridge: nothing to commit, so no frames are produced.
        return FramesToArray(env, {});
    }

    const jsize lat_length = env->GetArrayLength(latitudes_e6);
    const jsize lon_length = env->GetArrayLength(longitudes_e6);
    const jsize available = std::min(lat_length, lon_length);
    const jsize wanted = std::min<jsize>(
        std::min<jsize>(available, point_count),
        static_cast<jsize>(moto::ble::kMaxRoutePointsPerChunk));
    if (wanted < 2) return FramesToArray(env, {});

    std::vector<jint> lat(static_cast<std::size_t>(wanted));
    std::vector<jint> lon(static_cast<std::size_t>(wanted));
    env->GetIntArrayRegion(latitudes_e6, 0, wanted, lat.data());
    env->GetIntArrayRegion(longitudes_e6, 0, wanted, lon.data());

    moto::ble::RouteGeometry geometry;
    geometry.coordinate_system = moto::ble::CoordinateSystem::Gcj02;
    geometry.route_token =
        moto::ble::route_token(std::string_view(route_id_bytes));
    geometry.route_generation = static_cast<std::uint32_t>(generation);
    geometry.chunk_index = 0;
    geometry.chunk_count = 1;
    geometry.first_point_index = 0;
    geometry.total_point_count = static_cast<std::uint16_t>(wanted);
    geometry.view_origin.latitude_e6 = origin_latitude_e6;
    geometry.view_origin.longitude_e6 = origin_longitude_e6;
    geometry.points.reserve(static_cast<std::size_t>(wanted));
    for (jsize i = 0; i < wanted; ++i) {
        geometry.points.push_back(
            {lat[static_cast<std::size_t>(i)], lon[static_cast<std::size_t>(i)]});
    }
    return EncodeMessage(env, codec, moto::ble::Message{geometry}, 0);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeEncodeMapScene(
    JNIEnv* env, jclass, jlong handle, jobject input) {
    CodecHandle* codec = AsCodec(handle);
    if (codec == nullptr || input == nullptr) {
        ThrowRuntime(env, "codec handle is closed or input is null");
        return nullptr;
    }
    moto::ble::MapScene scene;
    if (!ReadMapScene(env, input, &scene)) {
        if (env->ExceptionCheck()) return nullptr;
        ThrowRuntime(env, "MotoMapSceneInput fields are missing");
        return nullptr;
    }
    return EncodeMessage(env, codec, moto::ble::Message{scene},
                         moto::ble::AckRequested);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativePushDeviceFrame(
    JNIEnv* env, jclass, jlong handle, jbyteArray frame, jlong received_at_ms,
    jobject out) {
    CodecHandle* codec = AsCodec(handle);
    if (codec == nullptr || frame == nullptr || out == nullptr) {
        ThrowRuntime(env, "codec handle is closed or argument is null");
        return 0;
    }

    const jsize length = env->GetArrayLength(frame);
    if (length <= 0) return 0;
    Bytes bytes(static_cast<std::size_t>(length));
    env->GetByteArrayRegion(frame, 0, length,
                            reinterpret_cast<jbyte*>(bytes.data()));

    const auto result = codec->reassembler.push(
        ByteView(bytes), static_cast<moto::ble::TimestampMs>(received_at_ms));

    FieldSet fields(env, kInboundBufferClass);
    if (fields.cls == nullptr) return 0;

    WriteInt(env, out, fields, "sequence",
             static_cast<jint>(result.message.sequence));
    WriteBool(env, out, fields, "ackRequested",
              (result.message.flags & moto::ble::AckRequested) != 0);
    WriteInt(env, out, fields, "errorCode",
             static_cast<jint>(result.error));
    WriteInt(env, out, fields, "errorOffset",
             static_cast<jint>(result.error_offset));

    switch (result.state) {
        case moto::ble::ReassemblyState::InProgress:
            WriteBool(env, out, fields, "complete", false);
            WriteBool(env, out, fields, "duplicate", false);
            WriteInt(env, out, fields, "kind", 0);
            return 1;
        case moto::ble::ReassemblyState::DuplicateFragment:
            WriteBool(env, out, fields, "complete", false);
            WriteBool(env, out, fields, "duplicate", true);
            WriteInt(env, out, fields, "kind", 0);
            return 3;
        case moto::ble::ReassemblyState::DuplicateMessage:
            WriteBool(env, out, fields, "complete", true);
            WriteBool(env, out, fields, "duplicate", true);
            WriteInt(env, out, fields, "kind", 0);
            return 4;
        case moto::ble::ReassemblyState::Error:
            WriteBool(env, out, fields, "complete", false);
            WriteBool(env, out, fields, "duplicate", false);
            WriteInt(env, out, fields, "kind", 0);
            return 0;
        case moto::ble::ReassemblyState::Complete:
            break;
    }

    const auto decoded = moto::ble::decode_message(
        result.message.type, ByteView(result.message.payload));
    if (!decoded.ok()) {
        WriteBool(env, out, fields, "complete", false);
        WriteInt(env, out, fields, "kind", 0);
        WriteInt(env, out, fields, "errorCode",
                 static_cast<jint>(decoded.error));
        WriteInt(env, out, fields, "errorOffset",
                 static_cast<jint>(decoded.offset));
        return 0;
    }

    WriteBool(env, out, fields, "complete", true);
    WriteBool(env, out, fields, "duplicate", false);

    if (const auto* status =
            std::get_if<moto::ble::ConnectionStatus>(&decoded.value)) {
        WriteInt(env, out, fields, "kind", 1);
        WriteInt(env, out, fields, "role", static_cast<jint>(status->role));
        WriteInt(env, out, fields, "state", static_cast<jint>(status->state));
        WriteInt(env, out, fields, "minimumVersion", status->minimum_version);
        WriteInt(env, out, fields, "maximumVersion", status->maximum_version);
        WriteInt(env, out, fields, "capabilities",
                 static_cast<jint>(status->capabilities));
        WriteInt(env, out, fields, "sessionId",
                 static_cast<jint>(status->session_id));
        WriteInt(env, out, fields, "maxFrameSize", status->max_frame_size);
        WriteInt(env, out, fields, "heartbeatIntervalMs",
                 status->heartbeat_interval_ms);
        return 2;
    }
    if (const auto* heartbeat =
            std::get_if<moto::ble::Heartbeat>(&decoded.value)) {
        WriteInt(env, out, fields, "kind", 2);
        WriteInt(env, out, fields, "sessionId",
                 static_cast<jint>(heartbeat->session_id));
        WriteInt(env, out, fields, "heartbeatMonotonicMs",
                 static_cast<jint>(heartbeat->monotonic_ms));
        WriteInt(env, out, fields, "heartbeatStatusFlags",
                 heartbeat->status_flags);
        return 2;
    }
    if (const auto* ack = std::get_if<moto::ble::Ack>(&decoded.value)) {
        WriteInt(env, out, fields, "kind", 3);
        WriteInt(env, out, fields, "ackSequence",
                 ack->acknowledged_sequence);
        WriteInt(env, out, fields, "ackStatus", static_cast<jint>(ack->status));
        WriteInt(env, out, fields, "ackCommandId", ack->command_id);
        return 2;
    }
    if (const auto* command =
            std::get_if<moto::ble::DeviceCommand>(&decoded.value)) {
        WriteInt(env, out, fields, "kind", 4);
        WriteInt(env, out, fields, "commandKind",
                 static_cast<jint>(command->kind));
        WriteInt(env, out, fields, "commandId", command->command_id);
        WriteInt(env, out, fields, "commandPage",
                 static_cast<jint>(command->page));
        WriteInt(env, out, fields, "commandX", command->x);
        WriteInt(env, out, fields, "commandY", command->y);
        WriteInt(env, out, fields, "commandEventTimeMs",
                 static_cast<jint>(command->event_time_ms));
        return 2;
    }

    // NavigationSnapshot / RouteGeometry / TrafficDeviation / MediaState /
    // MapScene are phone-to-device messages; a peripheral sending them is not
    // part of v1, so they surface as a complete but unhandled message.
    WriteInt(env, out, fields, "kind", 5);
    WriteInt(env, out, fields, "messageType",
             static_cast<jint>(result.message.type));
    return 2;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeRouteToken(
    JNIEnv* env, jclass, jbyteArray route_id) {
    const std::string bytes = BytesToString(env, route_id);
    return static_cast<jint>(
        moto::ble::route_token(std::string_view(bytes)));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeRunGoldenSelfTest(
    JNIEnv* env, jclass) {
    const std::vector<moto::android::GoldenCheck> checks =
        moto::android::RunAllSelfTests();
    std::vector<std::string> lines;
    lines.reserve(checks.size());
    for (const auto& check : checks) {
        lines.push_back((check.ok ? "PASS\t" : "FAIL\t") + check.name + "\t" +
                        check.detail);
    }
    return StringsToArray(env, lines);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeEnumValues(
    JNIEnv* env, jclass) {
    const jint values[] = {
        static_cast<jint>(moto::ble::EndpointRole::Phone),
        static_cast<jint>(moto::ble::EndpointRole::Device),
        static_cast<jint>(moto::ble::ConnectionState::Starting),
        static_cast<jint>(moto::ble::ConnectionState::Ready),
        static_cast<jint>(moto::ble::ConnectionState::Degraded),
        static_cast<jint>(moto::ble::ConnectionState::Closing),
        static_cast<jint>(moto::ble::NavigationState::Idle),
        static_cast<jint>(moto::ble::NavigationState::Acquiring),
        static_cast<jint>(moto::ble::NavigationState::Planning),
        static_cast<jint>(moto::ble::NavigationState::Navigating),
        static_cast<jint>(moto::ble::NavigationState::Rerouting),
        static_cast<jint>(moto::ble::NavigationState::Arrived),
        static_cast<jint>(moto::ble::Maneuver::Unknown),
        static_cast<jint>(moto::ble::Maneuver::Continue),
        static_cast<jint>(moto::ble::Maneuver::SlightLeft),
        static_cast<jint>(moto::ble::Maneuver::Left),
        static_cast<jint>(moto::ble::Maneuver::SharpLeft),
        static_cast<jint>(moto::ble::Maneuver::UTurnLeft),
        static_cast<jint>(moto::ble::Maneuver::SlightRight),
        static_cast<jint>(moto::ble::Maneuver::Right),
        static_cast<jint>(moto::ble::Maneuver::SharpRight),
        static_cast<jint>(moto::ble::Maneuver::UTurnRight),
        static_cast<jint>(moto::ble::Maneuver::Roundabout),
        static_cast<jint>(moto::ble::Maneuver::Exit),
        static_cast<jint>(moto::ble::Maneuver::Arrive),
        static_cast<jint>(moto::ble::TrafficLevel::Unknown),
        static_cast<jint>(moto::ble::TrafficLevel::FreeFlow),
        static_cast<jint>(moto::ble::TrafficLevel::Slow),
        static_cast<jint>(moto::ble::TrafficLevel::Congested),
        static_cast<jint>(moto::ble::TrafficLevel::Severe),
        static_cast<jint>(moto::ble::NetworkState::Offline),
        static_cast<jint>(moto::ble::NetworkState::Connecting),
        static_cast<jint>(moto::ble::NetworkState::Online),
        static_cast<jint>(moto::ble::DisplayPage::Navigation),
        static_cast<jint>(moto::ble::DisplayPage::Speed),
        static_cast<jint>(moto::ble::DisplayPage::Compass),
        static_cast<jint>(moto::ble::DisplayPage::Music),
        static_cast<jint>(moto::ble::AckStatus::Ok),
        static_cast<jint>(moto::ble::AckStatus::Unsupported),
        static_cast<jint>(moto::ble::AckStatus::InvalidState),
        static_cast<jint>(moto::ble::AckStatus::Failed),
        static_cast<jint>(moto::ble::AckStatus::Duplicate),
        static_cast<jint>(moto::ble::DeviceCommandKind::PageSelected),
        static_cast<jint>(moto::ble::DeviceCommandKind::Tap),
        static_cast<jint>(moto::ble::DeviceCommandKind::LongPress),
        static_cast<jint>(moto::ble::DeviceCommandKind::SwipeLeft),
        static_cast<jint>(moto::ble::DeviceCommandKind::SwipeRight),
        static_cast<jint>(moto::ble::DeviceCommandKind::SwipeUp),
        static_cast<jint>(moto::ble::DeviceCommandKind::SwipeDown),
        static_cast<jint>(moto::ble::DeviceCommandKind::MusicPrevious),
        static_cast<jint>(moto::ble::DeviceCommandKind::MusicTogglePlayback),
        static_cast<jint>(moto::ble::DeviceCommandKind::MusicNext),
        static_cast<jint>(moto::ble::DeviceCommandKind::MusicLike),
        static_cast<jint>(moto::ble::kFrameMagic),
        static_cast<jint>(moto::ble::kProtocolVersion),
        static_cast<jint>(moto::ble::kPayloadRevision),
        static_cast<jint>(moto::ble::kFrameHeaderSize),
        static_cast<jint>(moto::ble::kFrameCrcSize),
        static_cast<jint>(moto::ble::kMaxBleAttributeValueSize),
        static_cast<jint>(moto::ble::kMaxRoutePointsPerChunk),
        static_cast<jint>(moto::ble::kMaxMapSceneRoads),
        static_cast<jint>(moto::ble::kMaxMapSceneRoadPoints),
        static_cast<jint>(moto::ble::kMaxMapSceneBuildings),
        static_cast<jint>(moto::ble::kMaxMapSceneBuildingPoints),
        static_cast<jint>(moto::ble::kMaxTrafficSegments),
        static_cast<jint>(moto::ble::kUnknownTouchCoordinate),
    };
    const jsize size = static_cast<jsize>(sizeof(values) / sizeof(values[0]));
    jintArray result = env->NewIntArray(size);
    if (result != nullptr) {
        env->SetIntArrayRegion(result, 0, size, values);
    }
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_protocol_MotoProtocolCodec_nativeUuidStrings(
    JNIEnv* env, jclass) {
    return StringsToArray(env, {moto::ble::kServiceUuid,
                                moto::ble::kPhoneToDeviceUuid,
                                moto::ble::kDeviceToPhoneUuid,
                                moto::ble::kCccdUuid});
}

// ===========================================================================
// MotoNavCore
// ===========================================================================

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeCreate(
    JNIEnv*, jclass) {
    auto* holder =
        new std::unique_ptr<moto::nav::NavApp>(
            std::make_unique<moto::nav::NavApp>());
    return static_cast<jlong>(reinterpret_cast<intptr_t>(holder));
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeDestroy(
    JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<std::unique_ptr<moto::nav::NavApp>*>(
        static_cast<intptr_t>(handle));
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeReset(
    JNIEnv* env, jclass, jlong handle) {
    const auto commands = NavHandle(handle)->handle(moto::nav::Reset{});
    return CommandsToArray(env, commands);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeBeginNavigation(
    JNIEnv* env, jclass, jlong handle, jdouble longitude, jdouble latitude) {
    moto::nav::BeginNavigation event;
    event.destination.longitude_deg = longitude;
    event.destination.latitude_deg = latitude;
    const auto commands = NavHandle(handle)->handle(event);
    return CommandsToArray(env, commands);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeCancelNavigation(
    JNIEnv* env, jclass, jlong handle) {
    const auto commands =
        NavHandle(handle)->handle(moto::nav::CancelNavigation{});
    return CommandsToArray(env, commands);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeSetNetworkState(
    JNIEnv* env, jclass, jlong handle, jint state) {
    moto::nav::NetworkChanged event;
    event.state = static_cast<moto::nav::NetworkState>(state);
    const auto commands = NavHandle(handle)->handle(event);
    return CommandsToArray(env, commands);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeSelectDisplayPage(
    JNIEnv* env, jclass, jlong handle, jint page) {
    moto::nav::DisplayPageSelected event;
    event.page = static_cast<moto::nav::DisplayPage>(page);
    const auto commands = NavHandle(handle)->handle(event);
    return CommandsToArray(env, commands);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativePushFix(
    JNIEnv* env, jclass, jlong handle, jdouble longitude, jdouble latitude,
    jdouble accuracy_m, jdouble speed_mps, jdouble heading_deg,
    jlong timestamp_ms) {
    moto::nav::GnssFixReceived event;
    event.fix.position.longitude_deg = longitude;
    event.fix.position.latitude_deg = latitude;
    event.fix.accuracy_m = static_cast<float>(accuracy_m);
    event.fix.speed_mps = static_cast<float>(speed_mps);
    event.fix.heading_deg = static_cast<float>(heading_deg);
    event.fix.timestamp_ms = static_cast<moto::nav::TimestampMs>(timestamp_ms);
    const auto commands = NavHandle(handle)->handle(event);
    return CommandsToArray(env, commands);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeAcceptRoute(
    JNIEnv* env, jclass, jlong handle, jbyteArray route_id, jdoubleArray lat,
    jdoubleArray lon, jintArray maneuver_ids, jintArray maneuver_types,
    jdoubleArray maneuver_offsets, jobjectArray maneuver_road_names,
    jobjectArray maneuver_instructions, jintArray maneuver_exits,
    jdoubleArray traffic_starts, jdoubleArray traffic_ends,
    jintArray traffic_levels, jdouble total_distance_m, jint total_duration_s,
    jint speed_limit_kph, jlong generated_at_ms, jint request_id,
    jlong received_at_ms) {
    moto::nav::RouteReady event;
    event.request_id = static_cast<std::uint32_t>(request_id);
    event.received_at_ms = static_cast<moto::nav::TimestampMs>(received_at_ms);
    event.route.route_id = BytesToString(env, route_id);

    const std::vector<double> latitudes = ReadDoubleArray(env, lat);
    const std::vector<double> longitudes = ReadDoubleArray(env, lon);
    const std::size_t point_count =
        std::min(latitudes.size(), longitudes.size());
    event.route.polyline.reserve(point_count);
    for (std::size_t i = 0; i < point_count; ++i) {
        moto::nav::Gcj02Point point;
        point.latitude_deg = latitudes[i];
        point.longitude_deg = longitudes[i];
        event.route.polyline.push_back(point);
    }

    if (maneuver_ids != nullptr && maneuver_types != nullptr &&
        maneuver_offsets != nullptr && maneuver_road_names != nullptr &&
        maneuver_instructions != nullptr && maneuver_exits != nullptr) {
        const jsize count = env->GetArrayLength(maneuver_ids);
        if (env->GetArrayLength(maneuver_types) < count ||
            env->GetArrayLength(maneuver_offsets) < count ||
            env->GetArrayLength(maneuver_road_names) < count ||
            env->GetArrayLength(maneuver_instructions) < count ||
            env->GetArrayLength(maneuver_exits) < count) {
            ThrowRuntime(env, "maneuver arrays must share one length");
            return nullptr;
        }
        std::vector<jint> ids(static_cast<std::size_t>(count));
        std::vector<jint> types(static_cast<std::size_t>(count));
        std::vector<double> offsets(static_cast<std::size_t>(count));
        std::vector<jint> exits(static_cast<std::size_t>(count));
        if (count > 0) {
            env->GetIntArrayRegion(maneuver_ids, 0, count, ids.data());
            env->GetIntArrayRegion(maneuver_types, 0, count, types.data());
            env->GetDoubleArrayRegion(maneuver_offsets, 0, count, offsets.data());
            env->GetIntArrayRegion(maneuver_exits, 0, count, exits.data());
        }
        const std::vector<std::string> road_names =
            ReadStringArray(env, maneuver_road_names);
        const std::vector<std::string> instructions =
            ReadStringArray(env, maneuver_instructions);
        event.route.maneuvers.reserve(static_cast<std::size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            moto::nav::Maneuver maneuver;
            maneuver.id = static_cast<std::uint32_t>(ids[static_cast<std::size_t>(i)]);
            maneuver.type =
                static_cast<moto::nav::ManeuverType>(types[static_cast<std::size_t>(i)]);
            maneuver.route_offset_m = offsets[static_cast<std::size_t>(i)];
            maneuver.roundabout_exit =
                static_cast<std::uint8_t>(exits[static_cast<std::size_t>(i)]);
            if (static_cast<std::size_t>(i) < road_names.size()) {
                maneuver.road_name = road_names[static_cast<std::size_t>(i)];
            }
            if (static_cast<std::size_t>(i) < instructions.size()) {
                maneuver.instruction = instructions[static_cast<std::size_t>(i)];
            }
            event.route.maneuvers.push_back(std::move(maneuver));
        }
    }

    if (traffic_starts != nullptr && traffic_ends != nullptr &&
        traffic_levels != nullptr) {
        const jsize count = env->GetArrayLength(traffic_starts);
        if (env->GetArrayLength(traffic_ends) < count ||
            env->GetArrayLength(traffic_levels) < count) {
            ThrowRuntime(env, "traffic arrays must share one length");
            return nullptr;
        }
        std::vector<double> starts(static_cast<std::size_t>(count));
        std::vector<double> ends(static_cast<std::size_t>(count));
        std::vector<jint> levels(static_cast<std::size_t>(count));
        if (count > 0) {
            env->GetDoubleArrayRegion(traffic_starts, 0, count, starts.data());
            env->GetDoubleArrayRegion(traffic_ends, 0, count, ends.data());
            env->GetIntArrayRegion(traffic_levels, 0, count, levels.data());
        }
        event.route.traffic.reserve(static_cast<std::size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            moto::nav::TrafficSegment segment;
            segment.start_offset_m = starts[static_cast<std::size_t>(i)];
            segment.end_offset_m = ends[static_cast<std::size_t>(i)];
            segment.level =
                static_cast<moto::nav::TrafficLevel>(levels[static_cast<std::size_t>(i)]);
            event.route.traffic.push_back(segment);
        }
    }

    event.route.total_distance_m = total_distance_m;
    event.route.total_duration_s = static_cast<std::uint32_t>(total_duration_s);
    event.route.speed_limit_kph = static_cast<std::uint16_t>(speed_limit_kph);
    event.route.generated_at_ms =
        static_cast<moto::nav::TimestampMs>(generated_at_ms);

    const auto commands = NavHandle(handle)->handle(event);
    return CommandsToArray(env, commands);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeRejectRoute(
    JNIEnv* env, jclass, jlong handle, jint request_id, jboolean retryable,
    jlong received_at_ms) {
    moto::nav::RouteFailed event;
    event.request_id = static_cast<std::uint32_t>(request_id);
    event.retryable = retryable == JNI_TRUE;
    event.received_at_ms = static_cast<moto::nav::TimestampMs>(received_at_ms);
    const auto commands = NavHandle(handle)->handle(event);
    return CommandsToArray(env, commands);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeAcceptTraffic(
    JNIEnv* env, jclass, jlong handle, jbyteArray route_id, jdoubleArray starts,
    jdoubleArray ends, jintArray levels, jint remaining_duration_s,
    jint request_id, jlong observed_at_ms) {
    moto::nav::TrafficUpdated event;
    event.request_id = static_cast<std::uint32_t>(request_id);
    event.traffic.route_id = BytesToString(env, route_id);
    event.traffic.remaining_duration_s =
        static_cast<std::uint32_t>(remaining_duration_s);
    event.traffic.observed_at_ms =
        static_cast<moto::nav::TimestampMs>(observed_at_ms);

    const std::vector<double> starts_v = ReadDoubleArray(env, starts);
    const std::vector<double> ends_v = ReadDoubleArray(env, ends);
    std::vector<jint> levels_v;
    if (levels != nullptr) {
        const jsize count = env->GetArrayLength(levels);
        levels_v.resize(static_cast<std::size_t>(count));
        if (count > 0) {
            env->GetIntArrayRegion(levels, 0, count, levels_v.data());
        }
    }
    const std::size_t count = std::min(
        {starts_v.size(), ends_v.size(), levels_v.size()});
    event.traffic.segments.reserve(count);
    for (std::size_t i = 0; i < count; ++i) {
        moto::nav::TrafficSegment segment;
        segment.start_offset_m = starts_v[i];
        segment.end_offset_m = ends_v[i];
        segment.level = static_cast<moto::nav::TrafficLevel>(levels_v[i]);
        event.traffic.segments.push_back(segment);
    }

    const auto commands = NavHandle(handle)->handle(event);
    return CommandsToArray(env, commands);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeRejectTraffic(
    JNIEnv* env, jclass, jlong handle, jint request_id, jlong received_at_ms) {
    moto::nav::TrafficUpdateFailed event;
    event.request_id = static_cast<std::uint32_t>(request_id);
    event.received_at_ms = static_cast<moto::nav::TimestampMs>(received_at_ms);
    const auto commands = NavHandle(handle)->handle(event);
    return CommandsToArray(env, commands);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeTick(
    JNIEnv* env, jclass, jlong handle, jlong timestamp_ms) {
    moto::nav::Tick event;
    event.timestamp_ms = static_cast<moto::nav::TimestampMs>(timestamp_ms);
    const auto commands = NavHandle(handle)->handle(event);
    return CommandsToArray(env, commands);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeSimulateDeviation(
    JNIEnv* env, jclass, jlong handle) {
    const auto commands =
        NavHandle(handle)->handle(moto::nav::SimulateDeviation{});
    return CommandsToArray(env, commands);
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeSnapshot(
    JNIEnv* env, jclass, jlong handle, jobject out) {
    if (out == nullptr) return;
    WriteNavSnapshot(env, out, NavHandle(handle)->snapshot());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_MotoNavCore_nativeGoldenSelfTest(
    JNIEnv* env, jclass) {
    const std::vector<moto::android::GoldenCheck> checks =
        moto::android::RunAllSelfTests();
    std::vector<std::string> lines;
    lines.reserve(checks.size());
    for (const auto& check : checks) {
        lines.push_back((check.ok ? "PASS\t" : "FAIL\t") + check.name + "\t" +
                        check.detail);
    }
    return StringsToArray(env, lines);
}

// ---------------------------------------------------------------------------
// Coordinate system bridge.
//
// AMap's location SDK answers in GCJ-02, while the navigation pipeline is
// defined in WGS84 (the gateway is the only place that converts to GCJ-02 for
// AMap). A GCJ-02 fix therefore has to be brought back before it can be used,
// or the gateway shifts it a second time and every position ends up roughly
// 500 m off - a bug that looks like bad GPS rather than bad coordinates.
//
// The forward transform lives in upstream `shared/coordinates`; this inverse is
// a fixed-point iteration on that same function, so the two directions cannot
// drift apart and no second copy of the China-region offset maths exists.
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_io_github_bloodbear111_motogps_navigation_CoordinateConversion_nativeGcj02ToWgs84(
    JNIEnv* env, jobject, jdouble longitude_deg, jdouble latitude_deg) {
    const moto::coordinates::Coordinate gcj02{longitude_deg, latitude_deg};
    if (!moto::coordinates::is_valid_coordinate(gcj02)) return nullptr;

    // Outside mainland coverage the forward transform passes the value through
    // unchanged, so the same iteration lands on the input and does no harm.
    moto::coordinates::Coordinate wgs84 = gcj02;
    constexpr int kMaxIterations = 8;
    constexpr double kEpsilonDeg = 1e-9;
    for (int i = 0; i < kMaxIterations; ++i) {
        const moto::coordinates::ConversionResult forward =
            moto::coordinates::wgs84_to_gcj02(wgs84);
        if (!forward.ok()) return nullptr;
        const double delta_lng =
            forward.coordinate.longitude_deg - gcj02.longitude_deg;
        const double delta_lat =
            forward.coordinate.latitude_deg - gcj02.latitude_deg;
        if (std::abs(delta_lng) < kEpsilonDeg && std::abs(delta_lat) < kEpsilonDeg) {
            break;
        }
        wgs84.longitude_deg -= delta_lng;
        wgs84.latitude_deg -= delta_lat;
    }

    jdoubleArray result = env->NewDoubleArray(2);
    if (result == nullptr) return nullptr;
    const jdouble values[2] = {wgs84.longitude_deg, wgs84.latitude_deg};
    env->SetDoubleArrayRegion(result, 0, 2, values);
    return result;
}
