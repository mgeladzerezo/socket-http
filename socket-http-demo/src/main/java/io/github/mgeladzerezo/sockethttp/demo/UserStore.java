package io.github.mgeladzerezo.sockethttp.demo;

import io.github.mgeladzerezo.sockethttp.http.HttpException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/** A thread-safe in-memory user table, only there to give the JSON API something to do. */
final class UserStore {

    private final ConcurrentSkipListMap<Long, String> names = new ConcurrentSkipListMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    UserStore() {
        create("Ada Lovelace");
        create("Alan Turing");
        create("Grace Hopper");
    }

    List<Map<String, Object>> list() {
        List<Map<String, Object>> all = new ArrayList<>();
        names.forEach((id, name) -> all.add(view(id, name)));
        return all;
    }

    Map<String, Object> find(long id) {
        String name = names.get(id);
        if (name == null) {
            throw HttpException.notFound("no user " + id);
        }
        return view(id, name);
    }

    Map<String, Object> create(String name) {
        long id = nextId.getAndIncrement();
        names.put(id, name);
        return view(id, name);
    }

    Map<String, Object> rename(long id, String name) {
        if (names.replace(id, name) == null) {
            throw HttpException.notFound("no user " + id);
        }
        return view(id, name);
    }

    void delete(long id) {
        if (names.remove(id) == null) {
            throw HttpException.notFound("no user " + id);
        }
    }

    private static Map<String, Object> view(long id, String name) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", id);
        user.put("name", name);
        return user;
    }
}
