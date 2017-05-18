/*
 * Copyright © 2015 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.api.artifact;

import java.util.Objects;

/**
 * Represents a range of versions for an artifact. The lower version is inclusive and the upper version is exclusive.
 */
public class ArtifactRange extends ArtifactVersionRange {
  private final String namespace;
  private final String name;

  public ArtifactRange(String namespace, String name, ArtifactVersion lower, ArtifactVersion upper) {
    this(namespace, name, lower, true, upper, false);
  }

  public ArtifactRange(String namespace, String name, ArtifactVersion lower, boolean isLowerInclusive,
                       ArtifactVersion upper, boolean isUpperInclusive) {
    super(lower, isLowerInclusive, upper, isUpperInclusive);
    this.namespace = namespace;
    this.name = name;
  }

  public ArtifactRange(String namespace, String name, ArtifactVersionRange range) {
    this(namespace, name, range.lower, range.isLowerInclusive, range.upper, range.isUpperInclusive);
  }

  public String getNamespace() {
    return namespace;
  }

  public String getName() {
    return name;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    ArtifactRange that = (ArtifactRange) o;

    return Objects.equals(namespace, that.namespace) &&
      Objects.equals(name, that.name) &&
      Objects.equals(lower, that.lower) &&
      Objects.equals(upper, that.upper) &&
      isLowerInclusive == that.isLowerInclusive &&
      isUpperInclusive == that.isUpperInclusive;
  }

  @Override
  public int hashCode() {
    return Objects.hash(namespace, name, lower, isLowerInclusive, upper, isUpperInclusive);
  }

  @Override
  public String toString() {
    return toString(new StringBuilder().append(namespace).append(':'));
  }

  private String toString(StringBuilder builder) {
    return builder
      .append(name)
      .append(isLowerInclusive ? '[' : '(')
      .append(lower.getVersion())
      .append(',')
      .append(upper.getVersion())
      .append(isUpperInclusive ? ']' : ')')
      .toString();
  }

  /**
   * Parses the string representation of an artifact range, which is of the form:
   * {namespace}:{name}[{lower-version},{upper-version}]. This is what is returned by {@link #toString()}.
   * For example, default:my-functions[1.0.0,2.0.0) will correspond to an artifact name of my-functions with a
   * lower version of 1.0.0 and an upper version of 2.0.0 in the default namespace.
   *
   * @param artifactRangeStr the string representation to parse
   * @return the ArtifactRange corresponding to the given string
   */
  public static ArtifactRange parse(String artifactRangeStr) throws InvalidArtifactRangeException {
    // get the namespace
    int nameStartIndex = artifactRangeStr.indexOf(':');
    if (nameStartIndex < 0) {
      throw new InvalidArtifactRangeException(String.format("Invalid artifact range %s. " +
        "Could not find ':' separating namespace from artifact name.", artifactRangeStr));
    }
    String namespaceStr = artifactRangeStr.substring(0, nameStartIndex);
    // todo move validation logic to utils
//    String namespace;
//    try {
//      namespace = new NamespaceId(namespaceStr);
//    } catch (Exception e) {
//      throw new InvalidArtifactRangeException(String.format("Invalid namespace %s: %s",
// namespaceStr, e.getMessage()));
//    }

    // check not at the end of the string
    if (nameStartIndex == artifactRangeStr.length()) {
      throw new InvalidArtifactRangeException(
        String.format("Invalid artifact range %s. Nothing found after namespace.", artifactRangeStr));
    }

    return parse(namespaceStr, artifactRangeStr.substring(nameStartIndex + 1));
  }

  /**
   * Parses an unnamespaced string representation of an artifact range. It is expected to be of the form:
   * {name}[{lower-version},{upper-version}]. Square brackets are inclusive, and parentheses are exclusive.
   * For example, my-functions[1.0.0,2.0.0) will correspond to an artifact name of my-functions with a
   * lower version of 1.0.0 and an upper version of 2.0.0.
   *
   * @param namespace the namespace of the artifact range
   * @param artifactRangeStr the string representation to parse
   * @return the ArtifactRange corresponding to the given string
   */
  public static ArtifactRange parse(String namespace,
                                    String artifactRangeStr) throws InvalidArtifactRangeException {
    // search for the '[' or '(' between the artifact name and lower version
    int versionStartIndex = indexOf(artifactRangeStr, '[', '(', 0);
    if (versionStartIndex < 0) {
      throw new InvalidArtifactRangeException(
        String.format("Invalid artifact range %s. " +
                        "Could not find '[' or '(' indicating start of artifact lower version.", artifactRangeStr));
    }
    String name = artifactRangeStr.substring(0, versionStartIndex);
    // todo move validation logic to utils
//    if (!Id.Artifact.isValidName(name)) {
//      throw new InvalidArtifactRangeException(
//        String.format("Invalid artifact range %s. Artifact name '%s' is invalid.", artifactRangeStr, name));
//    }

    return new ArtifactRange(namespace, name,
                             ArtifactVersionRange.parse(artifactRangeStr.substring(versionStartIndex)));
  }
}
