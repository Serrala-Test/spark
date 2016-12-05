#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

"""
PySpark supports custom serializers for transferring data; this can improve
performance.

By default, PySpark uses L{PickleSerializer} to serialize objects using Python's
C{cPickle} serializer, which can serialize nearly any Python object.
Other serializers, like L{MarshalSerializer}, support fewer datatypes but can be
faster.

The serializer is chosen when creating L{SparkContext}:

>>> from pyspark.context import SparkContext
>>> from pyspark.serializers import MarshalSerializer
>>> sc = SparkContext('local', 'test', serializer=MarshalSerializer())
>>> sc.parallelize(list(range(1000))).map(lambda x: 2 * x).take(10)
[0, 2, 4, 6, 8, 10, 12, 14, 16, 18]
>>> sc.stop()

PySpark serialize objects in batches; By default, the batch size is chosen based
on the size of objects, also configurable by SparkContext's C{batchSize} parameter:

>>> sc = SparkContext('local', 'test', batchSize=2)
>>> rdd = sc.parallelize(range(16), 4).map(lambda x: x)

Behind the scenes, this creates a JavaRDD with four partitions, each of
which contains two batches of two objects:

>>> rdd.glom().collect()
[[0, 1, 2, 3], [4, 5, 6, 7], [8, 9, 10, 11], [12, 13, 14, 15]]
>>> int(rdd._jrdd.count())
8
>>> sc.stop()
"""

import sys
from itertools import chain, product
import marshal
import struct
import types
import collections
import zlib
import itertools

if sys.version < '3':
    import cPickle as pickle
    protocol = 2
    from itertools import izip as zip, imap as map
else:
    import pickle
    protocol = 3
    xrange = range

from pyspark import cloudpickle


__all__ = ["PickleSerializer", "MarshalSerializer", "UTF8Deserializer"]


class SpecialLengths(object):
    END_OF_DATA_SECTION = -1
    PYTHON_EXCEPTION_THROWN = -2
    TIMING_DATA = -3
    END_OF_STREAM = -4
    NULL = -5


class Serializer(object):

    def dump_stream(self, iterator, stream):
        """
        Serialize an iterator of objects to the output stream.
        """
        raise NotImplementedError

    def load_stream(self, stream):
        """
        Return an iterator of deserialized objects from the input stream.
        """
        raise NotImplementedError

    def _load_stream_without_unbatching(self, stream):
        return map(lambda x: [x], self.load_stream(stream))

    # Note: our notion of "equality" is that output generated by
    # equal serializers can be deserialized using the same serializer.

    # This default implementation handles the simple cases;
    # subclasses should override __eq__ as appropriate.

    def __eq__(self, other):
        return isinstance(other, self.__class__) and self.__dict__ == other.__dict__

    def __ne__(self, other):
        return not self.__eq__(other)

    def __repr__(self):
        return "%s()" % self.__class__.__name__

    def __hash__(self):
        return hash(str(self))


class FramedSerializer(Serializer):

    """
    Serializer that writes objects as a stream of (length, data) pairs,
    where C{length} is a 32-bit integer and data is C{length} bytes.
    """

    def __init__(self):
        # On Python 2.6, we can't write bytearrays to streams, so we need to convert them
        # to strings first. Check if the version number is that old.
        self._only_write_strings = sys.version_info[0:2] <= (2, 6)

    def dump_stream(self, iterator, stream):
        for obj in iterator:
            self._write_with_length(obj, stream)

    def load_stream(self, stream):
        while True:
            try:
                yield self._read_with_length(stream)
            except EOFError:
                return

    def _write_with_length(self, obj, stream):
        serialized = self.dumps(obj)
        if serialized is None:
            raise ValueError("serialized value should not be None")
        if len(serialized) > (1 << 31):
            raise ValueError("can not serialize object larger than 2G")
        write_int(len(serialized), stream)
        if self._only_write_strings:
            stream.write(str(serialized))
        else:
            stream.write(serialized)

    def _read_with_length(self, stream):
        length = read_int(stream)
        if length == SpecialLengths.END_OF_DATA_SECTION:
            raise EOFError
        elif length == SpecialLengths.NULL:
            return None
        obj = stream.read(length)
        if len(obj) < length:
            raise EOFError
        return self.loads(obj)

    def dumps(self, obj):
        """
        Serialize an object into a byte array.
        When batching is used, this will be called with an array of objects.
        """
        raise NotImplementedError

    def loads(self, obj):
        """
        Deserialize an object from a byte array.
        """
        raise NotImplementedError


class BatchedSerializer(Serializer):

    """
    Serializes a stream of objects in batches by calling its wrapped
    Serializer with streams of objects.
    """

    UNLIMITED_BATCH_SIZE = -1
    UNKNOWN_BATCH_SIZE = 0

    def __init__(self, serializer, batchSize=UNLIMITED_BATCH_SIZE):
        self.serializer = serializer
        self.batchSize = batchSize

    def _batched(self, iterator):
        if self.batchSize == self.UNLIMITED_BATCH_SIZE:
            yield list(iterator)
        elif hasattr(iterator, "__len__") and hasattr(iterator, "__getslice__"):
            n = len(iterator)
            for i in xrange(0, n, self.batchSize):
                yield iterator[i: i + self.batchSize]
        else:
            items = []
            count = 0
            for item in iterator:
                items.append(item)
                count += 1
                if count == self.batchSize:
                    yield items
                    items = []
                    count = 0
            if items:
                yield items

    def dump_stream(self, iterator, stream):
        self.serializer.dump_stream(self._batched(iterator), stream)

    def load_stream(self, stream):
        return chain.from_iterable(self._load_stream_without_unbatching(stream))

    def _load_stream_without_unbatching(self, stream):
        return self.serializer.load_stream(stream)

    def __repr__(self):
        return "BatchedSerializer(%s, %d)" % (str(self.serializer), self.batchSize)


class FlattenedValuesSerializer(BatchedSerializer):

    """
    Serializes a stream of list of pairs, split the list of values
    which contain more than a certain number of objects to make them
    have similar sizes.
    """
    def __init__(self, serializer, batchSize=10):
        BatchedSerializer.__init__(self, serializer, batchSize)

    def _batched(self, iterator):
        n = self.batchSize
        for key, values in iterator:
            for i in range(0, len(values), n):
                yield key, values[i:i + n]

    def load_stream(self, stream):
        return self.serializer.load_stream(stream)

    def __repr__(self):
        return "FlattenedValuesSerializer(%s, %d)" % (self.serializer, self.batchSize)


class AutoBatchedSerializer(BatchedSerializer):
    """
    Choose the size of batch automatically based on the size of object
    """

    def __init__(self, serializer, bestSize=1 << 16):
        BatchedSerializer.__init__(self, serializer, self.UNKNOWN_BATCH_SIZE)
        self.bestSize = bestSize

    def dump_stream(self, iterator, stream):
        batch, best = 1, self.bestSize
        iterator = iter(iterator)
        while True:
            vs = list(itertools.islice(iterator, batch))
            if not vs:
                break

            bytes = self.serializer.dumps(vs)
            write_int(len(bytes), stream)
            stream.write(bytes)

            size = len(bytes)
            if size < best:
                batch *= 2
            elif size > best * 10 and batch > 1:
                batch //= 2

    def __repr__(self):
        return "AutoBatchedSerializer(%s)" % self.serializer


class CartesianDeserializer(Serializer):

    """
    Deserializes the JavaRDD cartesian() of two PythonRDDs.
    """

    def __init__(self, key_ser, val_ser):
        self.key_ser = key_ser
        self.val_ser = val_ser

    def _load_stream_without_unbatching(self, stream):
        key_batch_stream = self.key_ser._load_stream_without_unbatching(stream)
        val_batch_stream = self.val_ser._load_stream_without_unbatching(stream)
        for (key_batch, val_batch) in zip(key_batch_stream, val_batch_stream):
            yield product(key_batch, val_batch)

    def load_stream(self, stream):
        return chain.from_iterable(self._load_stream_without_unbatching(stream))

    def __repr__(self):
        return "CartesianDeserializer(%s, %s)" % \
               (str(self.key_ser), str(self.val_ser))


class PairDeserializer(Serializer):

    """
    Deserializes the JavaRDD zip() of two PythonRDDs.
    """

    def __init__(self, key_ser, val_ser):
        self.key_ser = key_ser
        self.val_ser = val_ser

    def _load_stream_without_unbatching(self, stream):
        key_batch_stream = self.key_ser._load_stream_without_unbatching(stream)
        val_batch_stream = self.val_ser._load_stream_without_unbatching(stream)
        for (key_batch, val_batch) in zip(key_batch_stream, val_batch_stream):
            if len(key_batch) != len(val_batch):
                raise ValueError("Can not deserialize PairRDD with different number of items"
                                 " in batches: (%d, %d)" % (len(key_batch), len(val_batch)))
            yield zip(key_batch, val_batch)

    def load_stream(self, stream):
        return chain.from_iterable(self._load_stream_without_unbatching(stream))

    def __repr__(self):
        return "PairDeserializer(%s, %s)" % (str(self.key_ser), str(self.val_ser))


class NoOpSerializer(FramedSerializer):

    def loads(self, obj):
        return obj

    def dumps(self, obj):
        return obj


# Hook namedtuple, make it picklable

__cls = {}


def _restore(name, fields, value):
    """ Restore an object of namedtuple"""
    k = (name, fields)
    cls = __cls.get(k)
    if cls is None:
        cls = collections.namedtuple(name, fields)
        __cls[k] = cls
    return cls(*value)


def _hack_namedtuple(cls):
    """ Make class generated by namedtuple picklable """
    name = cls.__name__
    fields = cls._fields

    def __reduce__(self):
        return (_restore, (name, fields, tuple(self)))
    cls.__reduce__ = __reduce__
    cls._is_namedtuple_ = True
    return cls


def _hijack_namedtuple():
    """ Hack namedtuple() to make it picklable """
    # hijack only one time
    if hasattr(collections.namedtuple, "__hijack"):
        return

    global _old_namedtuple  # or it will put in closure

    def _copy_func(f):
        return types.FunctionType(f.__code__, f.__globals__, f.__name__,
                                  f.__defaults__, f.__closure__)

    _old_namedtuple = _copy_func(collections.namedtuple)

    def namedtuple(*args, **kwargs):
        cls = _old_namedtuple(*args, **kwargs)
        return _hack_namedtuple(cls)

    # replace namedtuple with new one
    collections.namedtuple.__globals__["_old_namedtuple"] = _old_namedtuple
    collections.namedtuple.__globals__["_hack_namedtuple"] = _hack_namedtuple
    collections.namedtuple.__code__ = namedtuple.__code__
    collections.namedtuple.__hijack = 1

    # hack the cls already generated by namedtuple
    # those created in other module can be pickled as normal,
    # so only hack those in __main__ module
    for n, o in sys.modules["__main__"].__dict__.items():
        if (type(o) is type and o.__base__ is tuple
                and hasattr(o, "_fields")
                and "__reduce__" not in o.__dict__):
            _hack_namedtuple(o)  # hack inplace


_hijack_namedtuple()


class PickleSerializer(FramedSerializer):

    """
    Serializes objects using Python's pickle serializer:

        http://docs.python.org/2/library/pickle.html

    This serializer supports nearly any Python object, but may
    not be as fast as more specialized serializers.
    """

    def dumps(self, obj):
        return pickle.dumps(obj, protocol)

    if sys.version >= '3':
        def loads(self, obj, encoding="bytes"):
            return pickle.loads(obj, encoding=encoding)
    else:
        def loads(self, obj, encoding=None):
            return pickle.loads(obj)


class CloudPickleSerializer(PickleSerializer):

    def dumps(self, obj):
        return cloudpickle.dumps(obj, 2)


class MarshalSerializer(FramedSerializer):

    """
    Serializes objects using Python's Marshal serializer:

        http://docs.python.org/2/library/marshal.html

    This serializer is faster than PickleSerializer but supports fewer datatypes.
    """

    def dumps(self, obj):
        return marshal.dumps(obj)

    def loads(self, obj):
        return marshal.loads(obj)


class AutoSerializer(FramedSerializer):

    """
    Choose marshal or pickle as serialization protocol automatically
    """

    def __init__(self):
        FramedSerializer.__init__(self)
        self._type = None

    def dumps(self, obj):
        if self._type is not None:
            return b'P' + pickle.dumps(obj, -1)
        try:
            return b'M' + marshal.dumps(obj)
        except Exception:
            self._type = b'P'
            return b'P' + pickle.dumps(obj, -1)

    def loads(self, obj):
        _type = obj[0]
        if _type == b'M':
            return marshal.loads(obj[1:])
        elif _type == b'P':
            return pickle.loads(obj[1:])
        else:
            raise ValueError("invalid sevialization type: %s" % _type)


class CompressedSerializer(FramedSerializer):
    """
    Compress the serialized data
    """
    def __init__(self, serializer):
        FramedSerializer.__init__(self)
        assert isinstance(serializer, FramedSerializer), "serializer must be a FramedSerializer"
        self.serializer = serializer

    def dumps(self, obj):
        return zlib.compress(self.serializer.dumps(obj), 1)

    def loads(self, obj):
        return self.serializer.loads(zlib.decompress(obj))

    def __repr__(self):
        return "CompressedSerializer(%s)" % self.serializer


class UTF8Deserializer(Serializer):

    """
    Deserializes streams written by String.getBytes.
    """

    def __init__(self, use_unicode=True):
        self.use_unicode = use_unicode

    def loads(self, stream):
        length = read_int(stream)
        if length == SpecialLengths.END_OF_DATA_SECTION:
            raise EOFError
        elif length == SpecialLengths.NULL:
            return None
        s = stream.read(length)
        return s.decode("utf-8") if self.use_unicode else s

    def load_stream(self, stream):
        try:
            while True:
                yield self.loads(stream)
        except struct.error:
            return
        except EOFError:
            return

    def __repr__(self):
        return "UTF8Deserializer(%s)" % self.use_unicode


def read_long(stream):
    length = stream.read(8)
    if not length:
        raise EOFError
    return struct.unpack("!q", length)[0]


def write_long(value, stream):
    stream.write(struct.pack("!q", value))


def pack_long(value):
    return struct.pack("!q", value)


def read_int(stream):
    length = stream.read(4)
    if not length:
        raise EOFError
    return struct.unpack("!i", length)[0]


def write_int(value, stream):
    stream.write(struct.pack("!i", value))


def write_with_length(obj, stream):
    write_int(len(obj), stream)
    stream.write(obj)


if __name__ == '__main__':
    import doctest
    (failure_count, test_count) = doctest.testmod()
    if failure_count:
        exit(-1)
