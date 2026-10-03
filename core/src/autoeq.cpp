#include "eqcore/autoeq.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <sstream>

namespace eqcore {

namespace {

std::string upper(std::string s) {
  for (auto& c : s) c = static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
  return s;
}

std::string trim(const std::string& s) {
  const auto b = s.find_first_not_of(" \t\r\n");
  if (b == std::string::npos) return "";
  const auto e = s.find_last_not_of(" \t\r\n");
  return s.substr(b, e - b + 1);
}

bool parseDouble(const std::string& tok, double& out) {
  char* end = nullptr;
  out = std::strtod(tok.c_str(), &end);
  return end != tok.c_str() && *end == '\0';
}

bool typeFromToken(const std::string& t, FilterType& out) {
  if (t == "PK" || t == "PEQ") out = FilterType::Peak;
  else if (t == "LS" || t == "LSC") out = FilterType::LowShelf;
  else if (t == "HS" || t == "HSC") out = FilterType::HighShelf;
  else if (t == "LP" || t == "LPQ") out = FilterType::LowPass;
  else if (t == "HP" || t == "HPQ") out = FilterType::HighPass;
  else if (t == "BP") out = FilterType::BandPass;
  else if (t == "NO") out = FilterType::Notch;
  else if (t == "AP") out = FilterType::AllPass;
  else return false;
  return true;
}

}  // namespace

ParametricPreset parseParametricEq(const std::string& text) {
  ParametricPreset preset;
  std::istringstream lines(text);
  std::string line;
  int lineNo = 0;
  while (std::getline(lines, line)) {
    ++lineNo;
    line = trim(line);
    if (line.empty() || line[0] == '#') continue;
    const std::string up = upper(line);

    if (up.rfind("PREAMP:", 0) == 0) {
      std::istringstream ss(line.substr(7));
      std::string v;
      double db;
      if (ss >> v && parseDouble(v, db)) preset.preampDb = db;
      else preset.warnings.push_back("line " + std::to_string(lineNo) + ": bad preamp");
      continue;
    }
    if (up.rfind("FILTER", 0) != 0) continue;  // ignore other APO commands

    const auto colon = line.find(':');
    if (colon == std::string::npos) {
      preset.warnings.push_back("line " + std::to_string(lineNo) + ": missing ':'");
      continue;
    }
    std::istringstream ss(line.substr(colon + 1));
    std::vector<std::string> tok;
    for (std::string t; ss >> t;) tok.push_back(t);
    if (tok.size() < 2) {
      preset.warnings.push_back("line " + std::to_string(lineNo) + ": too short");
      continue;
    }

    BandParams b;
    b.enabled = upper(tok[0]) == "ON";
    if (!typeFromToken(upper(tok[1]), b.type)) {
      preset.warnings.push_back("line " + std::to_string(lineNo) + ": unsupported type " + tok[1]);
      continue;
    }
    bool haveFc = false;
    for (size_t i = 2; i + 1 < tok.size(); ++i) {
      const std::string key = upper(tok[i]);
      double v;
      if (!parseDouble(tok[i + 1], v)) continue;
      if (key == "FC") { b.freqHz = v; haveFc = true; }
      else if (key == "GAIN") b.gainDb = v;
      else if (key == "Q") b.q = v;
    }
    if (!haveFc || b.freqHz <= 0) {
      preset.warnings.push_back("line " + std::to_string(lineNo) + ": missing Fc");
      continue;
    }
    preset.bands.push_back(b);
  }
  return preset;
}

std::vector<std::pair<double, double>> parseGraphicEq(const std::string& text) {
  std::vector<std::pair<double, double>> out;
  const auto colon = text.find(':');
  std::string body = colon == std::string::npos ? text : text.substr(colon + 1);
  std::istringstream entries(body);
  std::string entry;
  while (std::getline(entries, entry, ';')) {
    std::istringstream ss(entry);
    std::string fs, gs;
    double f, g;
    if (ss >> fs >> gs && parseDouble(fs, f) && parseDouble(gs, g) && f > 0) out.emplace_back(f, g);
  }
  std::sort(out.begin(), out.end());
  return out;
}

}  // namespace eqcore
