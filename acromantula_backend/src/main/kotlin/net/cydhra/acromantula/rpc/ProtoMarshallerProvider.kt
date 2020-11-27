package net.cydhra.acromantula.rpc

import com.google.api.grpc.kapt.GrpcMarshaller
import com.google.protobuf.Message
import io.grpc.MethodDescriptor
import io.grpc.protobuf.ProtoUtils

@GrpcMarshaller
object ProtoMarshallerProvider {
    @Suppress("UNCHECKED_CAST")
    fun <T : Message> of(type: Class<T>): MethodDescriptor.Marshaller<T> =
        ProtoUtils.marshaller(type.getMethod("getDefaultInstance").invoke(null) as T)
}