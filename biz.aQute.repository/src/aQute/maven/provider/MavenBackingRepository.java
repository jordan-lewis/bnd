package aQute.maven.provider;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import aQute.bnd.http.HttpClient;
import aQute.bnd.service.url.State;
import aQute.bnd.service.url.TaggedData;
import aQute.bnd.version.MavenVersion;
import aQute.lib.io.IO;
import aQute.lib.strings.Strings;
import aQute.libg.cryptography.SHA1;
import aQute.maven.api.Archive;
import aQute.maven.api.Program;
import aQute.maven.api.Revision;
import aQute.maven.provider.MetadataParser.ProgramMetadata;
import aQute.maven.provider.MetadataParser.RevisionMetadata;
import aQute.maven.provider.MetadataParser.SnapshotVersion;
import aQute.service.reporter.Reporter;

public abstract class MavenBackingRepository implements Closeable {
	final Map<Revision,RevisionMetadata>	revisions	= new ConcurrentHashMap<>();
	final Map<Program,ProgramMetadata>		programs	= new ConcurrentHashMap<>();
	final String							id;
	final File								local;
	final Reporter							reporter;

	public MavenBackingRepository(File root, String base, Reporter reporter) throws Exception {
		this.local = root;
		this.reporter = reporter;
		this.id = toName(base);
	}

	public static String toName(String uri) throws Exception {
		String s = SHA1.digest(uri.getBytes(StandardCharsets.UTF_8)).asHex();
		return s.substring(0, 8);
	}

	public abstract TaggedData fetch(String path, File file) throws Exception;

	protected void checkDigest(String fileSha, String remoteSha, File file) {
		if (remoteSha == null)
			return;

		try {
			int start = 0;
			while (start < remoteSha.length() && Character.isWhitespace(remoteSha.charAt(start)))
				start++;

			for (int i = 0; i < fileSha.length(); i++) {
				if (start + i < remoteSha.length()) {
					char us = fileSha.charAt(i);
					char them = remoteSha.charAt(start + i);
					if (us == them || Character.toLowerCase(us) == Character.toLowerCase(them))
						continue;
				}
				throw new IllegalArgumentException("Invalid checksum content " + remoteSha + " for " + file);
			}

		} catch (Exception e) {
			file.delete();
			throw e;
		}
	}

	public abstract void store(File file, String path) throws Exception;

	public abstract boolean delete(String path) throws Exception;

	public void close() {

	}

	public abstract URI toURI(String remotePath) throws Exception;

	public void getRevisions(Program program, List<Revision> revisions) throws Exception {
		ProgramMetadata meta = getMetadata(program);
		if (meta == null)
			return;

		for (MavenVersion v : meta.versions) {
			revisions.add(program.version(v));
		}
	}

	RevisionMetadata getMetadata(Revision revision) throws Exception {
		File metafile = IO.getFile(local, revision.metadata(id));
		RevisionMetadata metadata = revisions.get(revision);

		TaggedData tag = fetch(revision.metadata(), metafile);
		if (tag.getState() == State.NOT_FOUND || tag.getState() == State.OTHER) {
			if (metadata == null) {
				metadata = new RevisionMetadata();
				revisions.put(revision, metadata);
			}
			return metadata;
		}

		if (metadata == null || tag.getState() == State.UPDATED) {
			metadata = MetadataParser.parseRevisionMetadata(metafile);
			revisions.put(revision, metadata);
		}

		return metadata;
	}

	ProgramMetadata getMetadata(Program program) throws Exception {
		File metafile = IO.getFile(local, program.metadata(id));
		ProgramMetadata metadata = programs.get(program);

		TaggedData tag = fetch(program.metadata(), metafile);

		switch (tag.getState()) {
			case NOT_FOUND :
				return null;
			case OTHER :
				throw new IOException("Failed " + tag.getResponseCode());
			case UNMODIFIED :
				if (metadata != null)
					return metadata;

				// fall thru

			case UPDATED :
			default :
				metadata = MetadataParser.parseProgramMetadata(metafile);
				programs.put(program, metadata);
				return metadata;
		}
	}

	public List<Archive> getSnapshotArchives(Revision revision) throws Exception {
		RevisionMetadata metadata = getMetadata(revision);
		List<Archive> archives = new ArrayList<>();
		for (SnapshotVersion snapshotVersion : metadata.snapshotVersions) {
			Archive archive = revision.archive(snapshotVersion.value, snapshotVersion.extension,
					snapshotVersion.classifier);
			archives.add(archive);
		}

		return archives;
	}

	public MavenVersion getVersion(Revision revision) throws Exception {
		RevisionMetadata metadata = getMetadata(revision);
		if (metadata.snapshot.timestamp == null || metadata.snapshot.buildNumber == null) {
			reporter.warning("Snapshot and/or buildnumber not set %s in %s", metadata.snapshot, revision);
			return null;
		}

		return revision.version.toSnapshot(metadata.snapshot.timestamp, metadata.snapshot.buildNumber);
	}

	public String getId() {
		return id;
	}

	@Override
	public abstract String toString();

	public String getUser() throws Exception {
		return null;
	}

	static public List<MavenBackingRepository> create(String urls, Reporter reporter, File localRepo,
			HttpClient client) throws Exception {
		if (urls == null)
			return Collections.emptyList();

		List<MavenBackingRepository> result = new ArrayList<>();
		List<String> parts = Strings.split(urls);
		for (String part : parts) {
			MavenBackingRepository mbr = getBackingRepository(part, reporter, localRepo, client);
			result.add(mbr);
		}
		return result;
	}

	static public MavenBackingRepository getBackingRepository(String url, Reporter reporter, File localRepo,
			HttpClient client)
			throws Exception {
		url = clean(url);
		URI uri = new URI(url);
		if (uri.getScheme() == null) {
			File file = IO.getFile(uri.getPath());
			uri = file.toURI();
		}
		if (uri.getScheme().equalsIgnoreCase("file")) {
			File remote = new File(uri);
			return new MavenFileRepository(localRepo, remote, reporter);
		} else {
			return new MavenRemoteRepository(localRepo, client, url, reporter);
		}
	}

	static public String clean(String url) {
		if (url.endsWith("/"))
			return url;

		return url + "/";
	}

	public abstract boolean isFile();

}
